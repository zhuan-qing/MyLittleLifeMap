# 个人足迹地图 · 后端

Spring Boot 微服务后端，四个业务服务 + 中间件全部跑单机 Docker Compose。
实施口径以 [dev_doc.md](dev_doc.md) 为唯一依据。

当前进度：**P0 基础设施已完成**（中间件可连通、三库就绪、Flyway 基线可执行）。
业务代码从 P1 place-service 开始。

产品定位是**本机自用、桌面浏览器访问**：全部端口只绑 `127.0.0.1`，不做手机端适配、不做 HTTPS、不上公网（dev_doc v2.0）。

---

## 1. 环境决策：Docker Desktop，不开虚拟机

结论：**宿主机直接装 Docker Desktop（WSL2 后端）**，不要另起 VMware/VirtualBox/Hyper-V 虚拟机。

| 维度 | Docker Desktop | 虚拟机内 Docker |
| --- | --- | --- |
| 内存 | 共用 WSL2 一个轻量 VM，可精确限到 6GB | 宿主 8GB 起，同时跑 IDE 必炸 |
| 与宿主联通 | 容器端口直接映射到 `127.0.0.1`，IDEA 里的服务连 `localhost:5432` 就行 | NAT/桥接，容器 IP 每次重启漂移 |
| 文件挂载 | WSL2 本地 ext4，构建快 | 共享目录走 9p/vboxsf，Maven 构建慢 5–10 倍 |
| 与生产一致性 | 本地就是最终形态，不需要再迁 | 多一层虚拟化差异，出问题难定位 |

只有当公司安全策略禁止 Hyper-V/WSL2，或需要验证 systemd、iptables、真实多机网络时，才值得上虚拟机。

### 给 WSL2 限内存（必做）

中间件四容器 + 四个业务服务 JVM + Vite 常驻约 3.8GB（dev_doc §8.1）。在 `C:\Users\<你的用户名>\.wslconfig` 写入：

```ini
[wsl2]
memory=6GB
processors=4
swap=2GB
```

改完执行 `wsl --shutdown` 再启动 Docker Desktop 生效。不限的话 WSL2 默认会吃掉宿主一半内存，IDEA 会跟着卡。

---

## 2. 启动中间件

```bash
cp .env.example .env          # 首次；密码都在这里改
docker compose --env-file .env up -d
docker compose ps             # 等到 postgres / redis / minio / nacos 都是 healthy
./scripts/nacos-init.sh       # 建 namespace 与四个 group 的配置
./scripts/smoke-test.sh       # P0 验收
```

停止与清理：

```bash
docker compose down           # 停容器，保数据
docker compose down -v        # 连数据卷一起删 —— 会丢全部数据
```

## 3. 端口与凭据

| 组件 | 宿主端口 | 账号 / 密码 | 说明 |
| --- | --- | --- | --- |
| PostgreSQL | 5432 | `lifemap` / `lifemap_dev_2026` | 库：`place_db`、`media_db`、`nacos` |
| Redis | 6379 | 密码同上 | AOF 开启，maxmemory 96MB |
| MinIO API | 9000 | `minioadmin` / `minioadmin123` | 桶：`lifemap`、`lifemap-backup` |
| MinIO 控制台 | 9001 | 同上 | http://127.0.0.1:9001 |
| Nacos | 8848 / 9848 | 开发环境关鉴权 | http://127.0.0.1:8848/nacos |

四个业务服务（宿主直跑时）：gateway 8080、place 8081、media 8082、stats 8083；前端 Vite 5173，`/api` 代理到 8080。

所有端口都绑在 `127.0.0.1`（含 compose 里的中间件），同一个 Wi-Fi 下的其他设备访问不到。

> `.env` 已进 `.gitignore`。dev_doc §8.2 要求按环境变化的配置走 Nacos、密码不进代码库。

## 4. 结构

```
pom.xml                     聚合父 POM，统一版本
lifemap-common/             统一响应体 ApiResponse、错误码、链路头常量（无第三方依赖）
lifemap-gateway/            8080 路由与鉴权，反应式栈
lifemap-place-service/      8081 地点与地理编码，独占 place_db
lifemap-media-service/      8082 图片管线，独占 media_db + MinIO
lifemap-stats-service/      8083 省市点亮聚合，无库，结果进 Redis
docker-compose.yml          P0 中间件编排（业务服务跑宿主机 JVM，不打镜像）
deploy/postgres/init/       首次启动建三库，幂等
deploy/nacos/<GROUP>/       Nacos 配置源文件，改完重跑 nacos-init.sh
scripts/nacos-init.sh       namespace + 四组配置，幂等
scripts/smoke-test.sh       P0 验收
```

### 数据归属（硬约束）

- `place_db`：`place`、`place_event`、`app_setting`，只有 place-service 能写
- `media_db`：`photo`，只有 media-service 能写
- `stats` 无自有库——点亮是查询结果不是状态，不建点亮表（dev_doc §1）
- 跨服务取数一律走 API，禁止跨库 JOIN。stats 要 adcode 就调 `GET /internal/places/codes`

### Nacos 命名

- namespace：本地只有一个环境，统一用 `lifemap-dev`，不要多建（多建只会在控制台里点错）
- group 按服务：`GATEWAY` / `PLACE` / `MEDIA` / `STATS`
- dataId：`gateway.yaml`、`place-service.yaml`、`media-service.yaml`、`stats-service.yaml`

服务端用 `spring.config.import: optional:nacos:<dataId>?group=<GROUP>` 拉配置。本地 `application.yaml` 只放启动必需的兜底默认值，凡按环境变化的项放 Nacos——改配置不用重新打镜像。

## 5. P0 验收口径

`./scripts/smoke-test.sh` 全绿即通过，逐项对应 dev_doc §9 的 P0 交付物：

1. 五个容器运行且 healthy
2. PostgreSQL 可连接，`place_db` / `media_db` / `nacos` 三库存在
3. Redis `PING` 返回 `PONG`
4. MinIO 健康检查通过，`lifemap` 桶存在且禁止匿名访问
5. Nacos readiness 为 UP，`lifemap-dev` namespace 下四组配置齐全
6. 四个业务服务探活（可选，服务没起只提示不算失败）

---

## 6. 常见坑

**PG 数据目录**：compose 把卷挂到 `/var/lib/postgresql` 而不是 `.../data`。因为 PG 18 起官方镜像把 `PGDATA` 挪到了 `/var/lib/postgresql/18/docker`，挂父目录 17/18 通用，不会出现"升级镜像后数据消失"。

**三库没建出来**：`deploy/postgres/init/*.sh` 只在数据卷为空的首次启动执行。已经 `up` 过又改了初始化脚本，得 `docker compose down -v` 重来。

**Nacos 连不上**：除了 8848，2.x 还要 9848（gRPC，端口号 = 8848 + 1000）。宿主防火墙放开这两个，否则服务注册一直转圈。

**镜像标签拉不到**：`postgres:18.6`、`nacos/nacos-server:v2.3.2` 是钉死的版本，拉不到就在 `.env` 里改 `POSTGRES_IMAGE_TAG` / `NACOS_IMAGE_TAG`。

**端口被占**：`.env` 里改 `POSTGRES_PORT` / `REDIS_PORT` / `NACOS_PORT` 等，服务端配置读的是同一批环境变量。

**Nacos 配置持久化**：当前 standalone 用内嵌 Derby + 数据卷，重启不丢，但不支持集群。第三个库 `nacos` 是给"切外部数据源"预留的，P0 不用；真要切得引 PostgreSQL 数据源插件，属额外改造，不在 MVP 范围内。

## 7. 下一步

P1 place-service：实体与 Mapper、地点 CRUD、高德 POI 与逆地理代理、WGS-84 → GCJ-02 转换。
高德 Web 服务 Key 填在 `.env` 的 `AMAP_WEB_KEY` 或 Nacos 的 `PLACE/place-service.yaml`。
