# 个人足迹地图 · 开发实施方案

**文档版本**：v2.0（本地桌面版）　**编制日期**：2026 年 11 月 7 日　**前置文档**：《个人足迹地图产品调研与 MVP 实施方案》v2.0

**读者与用途**：本文面向开发人员，是开发期间的唯一实施依据。产品层面的"为什么这样取舍"见前置文档，本文只规定"怎么做"——技术栈、服务边界、数据模型、接口契约、实现规约、部署拓扑与验收标准。

**本版定位**：产品只在自己电脑上用，数据只入本机数据库，不做公网部署、不做手机端。服务拆分保持不变（四个业务服务锁死），变化的只是访问形态、前端选型、部署拓扑与验收口径。

## 0. 相对 v1.0 的变更

| 变化点 | v1.0 | v2.0 | 理由 |
| --- | --- | --- | --- |
| 访问形态 | 手机浏览器 + 添加到主屏幕 | 桌面浏览器 `http://localhost` | 本地自用，桌面是唯一主场景 |
| 前端组件库 | Vant（移动端） | Element Plus（桌面端） | Vant 在宽屏上排版失真，表格、侧栏、右键菜单都缺 |
| 页面布局 | 全屏地图 + 底部弹层 | 三栏：左时间轴 / 中地图 / 右详情 | 桌面有横向空间，三栏能少点九成跳转 |
| 图片规格 | 1600px WebP / 400px 缩略 | 2048px WebP / 480px 缩略 | 宽屏 + HiDPI，1600 在 1440 布局下发虚 |
| 入口 | Caddy + HTTPS 443 | Vite dev / preview，明文 localhost | 本地无域名无证书，反代纯属多余 |
| 容器数 | 9 个（4 中间件 + 4 服务 + caddy） | 4 个中间件；Java 服务与前端跑宿主机 | 改代码热重启，不必反复打镜像 |
| 备份 | 推对象存储 / NAS | 本地目录 + 移动硬盘，手动或任务计划 | 没有服务器，也没有异地存储 |
| 架构 | 四服务 + Nacos + Redis + MinIO | **不变** | 拆分本身没问题，不动 |

## 1. 全局约束

以下约束贯穿全部开发工作，任何设计决策不得与之冲突。

| 约束项 | 规定 |
| --- | --- |
| 服务拆分 | 网关 + 地点 + 媒体 + 统计，共 4 个业务服务，锁死不再增加；新需求一律进 backlog |
| 部署形态 | 本地单机：Docker Desktop（WSL2）起 4 个中间件容器，4 个 Java 服务跑宿主机 JVM，前端跑 Vite；一条脚本起停 |
| 访问形态 | 桌面浏览器（Chrome / Edge），最小可用宽度 1280，主用 1440 及以上；**不做手机端适配，不做响应式断点，不做 PWA** |
| 网络暴露 | 所有端口只绑 `127.0.0.1`，不对局域网开放；无 HTTPS、无域名、无反向代理 |
| 数据归属 | 每个服务只写自己的库，跨服务读数据走 API，禁止跨库 JOIN |
| 鉴权 | JWT 只在网关校验一次，向下游透传 X-User-Id 与 traceId；服务间直连，不重复鉴权，端口不外露 |
| 一致性 | 不引入分布式事务（不用 Seata）；删除走软删 + 事件表，media 定时对账清理孤儿照片，允许分钟级不一致 |
| 点亮口径 | 点亮是查询结果，不是状态，不建点亮表 |
| 数据安全 | 数据只在本机，硬盘坏即全丢。备份是一等功能，不是可选项（见 §8.4） |

## 2. 功能范围

### 2.1 需求清单

| 编号 | 需求 | 优先级 | MVP |
| --- | --- | --- | --- |
| R01 | 搜索地点添加，自动带出坐标与行政区 | P0 | 包含 |
| R02 | 地图单击选点添加（右键取消选点） | P0 | 包含 |
| R03 | 填写到访日期、1–5 星评分、一句话笔记 | P0 | 包含 |
| R04 | 单条记录上传多张照片 | P0 | 包含 |
| R05 | 省 / 市两级点亮视图，按到访数着色 | P0 | 包含 |
| R06 | 统计卡：已点亮省份、城市、地点总数 | P0 | 包含 |
| R07 | 地点列表，按时间倒序，支持年份筛选 | P0 | 包含 |
| R08 | 详情页：照片大图与切换、笔记、地图定位 | P0 | 包含 |
| R09 | 单用户登录（JWT） | P0 | 包含 |
| R10 | 全量导出：JSON + 原图打包 | P0 | 包含 |
| R11 | 批量导入相册（EXIF 自动定位） | P1 | 缓做 |
| R12 | 足迹海报生成 | P1 | 缓做 |
| R13 | 按省份聚合浏览 + 关键词搜索 | P1 | 缓做 |
| R14 | 轨迹线记录与回放 | P2 | 不做 |
| R15 | 同行人、心愿清单 | P2 | 不做 |

### 2.2 范围说明

- **MVP 包含**：R01–R10，覆盖打卡、照片、点评、点亮、列表详情、登录、导出的完整闭环。
- **明确不做**：手机端适配、响应式布局、PWA、轨迹线、批量导入、分享海报、评论点赞、多用户、区县级点亮、原生 App、离线地图、公网部署与 HTTPS。
- **R11 缓做理由**：批量导入依赖 EXIF 解析、批量逆地理编码、草稿审核三个子系统，等于再造半个产品；先用"手动补 10 条"验证交互，再决定是否投入。

### 2.3 桌面端交互基线

只做一套布局，不做断点。界面固定三栏，最小窗口 1280×800，横向空间不足时优先压缩侧栏而不是隐藏。

| 区域 | 内容 | 快捷键 |
| --- | --- | --- |
| 左栏 320px | 年份筛选 + 时间轴列表（缩略图 + 名称 + 日期 + 星级） | `↑` `↓` 移动选中，`Enter` 打开详情 |
| 中栏 自适应 | 高德地图，标记点聚合，点击标记选中 | `+` `-` 缩放，`Esc` 关闭详情 |
| 右栏 420px | 详情：照片大图 + 缩略条、笔记、评分、坐标、编辑 / 删除 | `←` `→` 切换照片，`Ctrl+S` 保存 |

新增记录走右栏的表单态，不弹模态框——弹窗在桌面端会挡住地图，选点时要同时看着地图和表单。

## 3. 技术栈

| 层次 | 选型 | 说明 |
| --- | --- | --- |
| 运行时 | JDK 21 + Maven 3.9（Wrapper 锁定） | Spring Boot 3.3 基线 |
| 微服务框架 | Spring Boot 3.3 + Spring Cloud 2023.0.x | Gateway / OpenFeign / LoadBalancer |
| 注册与配置 | Nacos 2.3 | 注册中心与配置中心同一实例；本地单 namespace，group 按服务拆分 |
| 持久层 | MyBatis-Plus 3.5 | BaseMapper 免写单表 CRUD，聚合用注解 SQL |
| 数据库 | PostgreSQL 18，一实例三库 | 每个服务独立 database |
| 缓存 | Redis 7 | 统计结果缓存、登录态 |
| 对象存储 | MinIO | 照片与缩略图，按 年/月 分前缀 |
| 数据迁移 | Flyway | 每个服务管理自己的 migration |
| 前端 | Vue 3 + Vite + Element Plus + Pinia | 单体 SPA，桌面三栏布局，直接调网关，不做 BFF |
| 地图与图表 | 高德 JS API 2.0 + ECharts 5 | 底图自带审图号，点亮版图用行政区 GeoJSON |
| 运行方式 | Docker Compose（仅中间件）+ 宿主机 JVM + Vite | 中间件容器化，业务代码热重启 |

**为什么不把 Java 服务也放进容器跑**：改一行代码要重新打镜像、重启容器、等 20 秒，一天几十次开发循环扛不住。中间件稳定、业务多变，所以只把稳定的那部分容器化。

**为什么去掉 Caddy**：v1.0 用它是为了公网 HTTPS 和静态资源托管。本地访问 `localhost` 本身就在浏览器安全上下文里（高德 JS API、剪贴板、地理位置都能用），不需要证书；静态资源由 Vite 托管。等高德 Key 申请时，白名单填 `localhost` 即可。

## 4. 服务架构

### 4.1 服务清单

| 服务 | 职责 | 端口 | 数据归属 |
| --- | --- | --- | --- |
| gateway | 路由、JWT 校验、限流、统一日志入口、生成 traceId | 8080 | 无（Redis 存 token 状态） |
| place-service | 地点 CRUD、时间轴、导出、高德 POI 与逆地理代理、坐标转换 | 8081 | place_db |
| media-service | 照片上传、方向纠正、WebP 与缩略图生成、MinIO 读写 | 8082 | media_db + MinIO |
| stats-service | 省市点亮聚合、统计卡、缓存 | 8083 | 无库，Redis 缓存 |

**拆分依据**：按"谁拥有数据"拆服务，四个服务对上四类数据——地点、照片、统计、入口。高德代理与坐标转换不单独拆服务（只有 place 调用，多拆只是多一次网络跳转）；待 media 的批量导入也要用时，再抽成 geo-service。

### 4.2 请求链路

| 路径 | 链路 |
| --- | --- |
| 入口 | 浏览器（localhost:5173）→ Vite dev server proxy → gateway（JWT 校验，生成 traceId） |
| /api/places/** | gateway → place-service → PostgreSQL(place_db)；地理接口 → 高德 Web 服务（POI / 逆地理） |
| /api/photos/** | gateway → media-service → MinIO + PostgreSQL(media_db) |
| /api/stats/** | gateway → stats-service →（OpenFeign）→ place-service 内部接口 → Redis（统计结果缓存 5 分钟） |
| 服务注册 | 所有服务启动时向 Nacos 注册，网关按服务名负载均衡 |

Vite 里只需一条代理规则：`/api` → `http://127.0.0.1:8080`。生产态（`vite preview`）同样适用，因此前后端不存在跨域问题，也不需要配 CORS。

### 4.3 编码硬约定

1. **数据归属**：stats 需要 adcode 时调 place 的内部接口，不直连 place_db。
2. **鉴权与链路**：JWT 仅网关校验；网关为每个请求生成 traceId 并全链路透传，日志按 traceId 聚合。
3. **一致性**：删除地点用软删 + 事件表；media 的定时对账任务清理孤儿照片；单用户场景不做分布式事务。
4. **端口绑定**：所有服务 `server.address=127.0.0.1`，MinIO / PG / Redis / Nacos 的宿主端口同样只绑回环地址，避免同一个 Wi-Fi 下的其他设备摸进来。

## 5. 数据模型

三张表分属两个库。坐标与时间字段类型必须一次定对。

### 5.1 表结构

**place（place_db）**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGSERIAL | 主键 |
| name | VARCHAR | 地点名称 |
| lng / lat | NUMERIC(10,6) | 坐标，存 GCJ-02 |
| lng_wgs / lat_wgs | NUMERIC(10,6) | WGS-84 原始坐标，换底图用 |
| adcode | CHAR(6) | 行政区划代码 |
| province / city | VARCHAR | 省、市 |
| visited_on | DATE | 到访日期 |
| rating | SMALLINT | 评分 1–5 |
| note | TEXT | 一句话笔记 |
| deleted_at | TIMESTAMPTZ | 软删时间，NULL 为有效 |

**photo（media_db）**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGSERIAL | 主键 |
| place_id | BIGINT | 所属地点 |
| object_key | VARCHAR | MinIO 原图 key |
| webp_key | VARCHAR | 详情大图 key（2048px WebP） |
| thumb_key | VARCHAR | MinIO 缩略图 key（480px） |
| width / height | INT | 尺寸 |
| bytes | BIGINT | 文件大小 |
| taken_at | TIMESTAMPTZ | 拍摄时间 |
| deleted_at | TIMESTAMPTZ | 软删时间，NULL 为有效 |

**place_event（place_db）**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGSERIAL | 主键 |
| place_id | BIGINT | 关联地点 |
| event_type | VARCHAR | 事件类型，当前仅 PLACE_DELETED |
| status | SMALLINT | 0 待处理 / 1 已处理 / 2 失败 |
| created_at / processed_at | TIMESTAMPTZ | 写入与处理时间 |

**app_setting（place_db）**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| key | VARCHAR(50) PK | 配置键（密码哈希、地图 Key 等） |
| value | TEXT | 配置值 |
| updated_at | TIMESTAMPTZ | 更新时间 |

### 5.2 索引设计

仅两个索引：place(visited_on desc) 支撑时间轴翻页；place(adcode) 支撑点亮聚合。不建"点亮表"——点亮是查询结果，不是状态。

### 5.3 本地库位

单实例三库：`place_db`、`media_db`、`nacos`（第三个仅预留，本地跑 derby 用不上）。数据落在 Docker 命名卷里，路径在 `\\wsl$\docker-desktop-data\...`，不要手动去改；要备份走 §8.4 的脚本。

## 6. 接口契约

### 6.1 接口清单

| 服务 | 接口 | 说明 |
| --- | --- | --- |
| gateway | POST /api/login | 密码换 JWT |
| place | GET / POST /api/places | 列表（分页、年份筛选）与新增 |
| place | GET / PUT / DELETE /api/places/{id} | 详情、修改、软删 |
| place | GET /api/geo/search、/api/geo/regeo | 高德 POI 与逆地理代理 |
| place | GET /internal/places/codes | 供 stats 拉 adcode 列表，仅内网可访问 |
| media | POST /api/places/{id}/photos | 多文件上传，返回缩略图地址 |
| media | DELETE /api/photos/{id} | 删 MinIO 对象 + 软删记录 |
| stats | GET /api/stats/lightup | 省市点亮列表与计数 |
| place | GET /api/export | 打包 JSON 与全部原图 |

### 6.2 通用约定

- 时间统一 ISO-8601；列表接口统一分页参数 `page` / `size`，默认 20；错误码全服务统一，联调阶段（P6）完成对齐。
- 列表接口的返回体带 `total`，桌面端左栏要靠它显示"共 N 条"并做滚动加载。
- 图片地址一律返回可直接渲染的 URL（media 生成带签名的临时地址），前端不拼 MinIO 路径。

## 7. 关键实现规约

**① 坐标转换（收在 place-service）**：照片 EXIF 是 WGS-84，高德底图是 GCJ-02，国内相差 300–800 米。入库前统一转 GCJ-02，同时保留 WGS-84 原值以便换底图。

**② 点亮不落库（stats-service）**：从 place 拉 adcode 列表，用 left(adcode,2) 拼接 0000 聚合省份、left(adcode,4) 拼接 00 聚合城市，结果放 Redis 缓存 5 分钟。改判定口径只改聚合 SQL 一处。

**③ 图片管线一次做对（media-service）**：校验类型与大小 → 按 EXIF 纠正方向 → 生成 2048px WebP（详情大图）与 480px 缩略图（时间轴与网格）→ 原图按 年/月/uuid 存入 MinIO。原图永不修改。

尺寸口径由桌面布局反推：右栏详情区约 780×560 逻辑像素，2 倍屏需要 1560px，取 2048 留余量给全屏看图；左栏缩略图 72×72、点亮网格 240px 宽，2 倍屏取 480。手机上 1600/400 够用，桌面不够。

**④ 软删与对账**：删除地点写软删标记 + 事件表；media 每日对账任务清理孤儿照片，孤儿对象超过 7 天自动删除。

**⑤ 地图交互（前端）**：桌面鼠标语义与触摸不同，禁止照搬手势——单击选点、右键弹"在此添加"，拖拽移动地图，滚轮缩放。标记点超过 200 个时开高德聚合（`MarkerClusterer`），否则 1440 宽屏上会糊成一片。

**⑥ 大图查看（前端）**：详情页照片主图用 `object-fit: contain`，点击进 `el-image-viewer` 全屏并支持方向键切换。不要做成移动端那种左右滑动轮播——鼠标拖拽轮播的体验很差。

## 8. 本地运行

### 8.1 进程与资源

| 进程 | 形态 | 端口 | 内存上限 |
| --- | --- | --- | --- |
| postgres | 容器 | 5432 | 768MB |
| redis | 容器 | 6379 | 128MB |
| minio | 容器 | 9000 / 9001 | 384MB |
| nacos | 容器 | 8848 / 9848 | 512MB |
| gateway | 宿主机 JVM | 8080 | 384MB |
| place-service | 宿主机 JVM | 8081 | 512MB |
| media-service | 宿主机 JVM | 8082 | 512MB |
| stats-service | 宿主机 JVM | 8083 | 384MB |
| vite | 宿主机 Node | 5173 | 300MB |

常驻约 3.8GB。中间件用 compose 起，全部配 healthcheck；应用侧用 Nacos 服务名做依赖，不靠启动顺序硬凑——某个服务晚起几秒，网关重试一次就恢复。

**WSL2 内存必须限**：在 `C:\Users\<用户名>\.wslconfig` 写 `memory=6GB`，否则 WSL 默认吃掉宿主一半内存，IDEA 会跟着卡。

### 8.2 Nacos 配置规约

单机模式即可。服务注册到同一 namespace（`lifemap-dev`）；配置按 group 拆分（gateway / place / media / stats），改数据库连接串不用重新打镜像。配置变更走 Nacos 控制台，服务侧加 `@RefreshScope` 热更新。

本地只有一个环境，**不要建多 namespace**——`lifemap-dev` 一个就够，多建只会让自己在控制台里点错。

### 8.3 启动顺序

```bash
# 1. 中间件（首次执行一次 nacos 初始化）
docker compose --env-file .env up -d
./scripts/nacos-init.sh

# 2. 首次拉代码后先装父工程与 common 到本地仓库，否则 -pl 单模块跑会找不到依赖
./mvnw -DskipTests install

# 3. 后端四服务（各自一个终端，或 IDEA 里一起跑）
./mvnw -pl lifemap-gateway       spring-boot:run
./mvnw -pl lifemap-place-service spring-boot:run
./mvnw -pl lifemap-media-service spring-boot:run
./mvnw -pl lifemap-stats-service spring-boot:run

# 4. 前端
cd MyLittleLifeMap-frontend && npm run dev

# 5. 自检
./scripts/smoke-test.sh
```

P7 把上面这些步骤收进 `scripts/dev-up.sh`（Windows 用 `.cmd`），开机只跑一条命令。

### 8.4 备份

本地没有异地存储，备份靠外接硬盘。Windows 任务计划每日 03:00 执行：

1. `pg_dump` 导出 `place_db` 与 `media_db`，`gzip` 后落到 `D:\lifemap-backup\db\yyyyMMdd\`
2. `mc mirror` 把 MinIO 的 `lifemap` 桶同步到 `D:\lifemap-backup\minio\`
3. 导出 Nacos 配置到同名目录
4. 保留 30 天，超期自动删；每月把 `D:\lifemap-backup` 整目录拷一次到移动硬盘

每月在测试库跑一次 `pg_restore` 恢复演练——没验证过的备份等于没有备份。

## 9. 开发排期

| 阶段 | 任务 | 交付物 | 工期 |
| --- | --- | --- | --- |
| P0 基础设施 | 父 POM 与 Wrapper、compose 起 PG/Redis/MinIO/Nacos、Nacos 建 namespace 与四个 group、三个空库与 Flyway 基线 | 中间件全部可连通 | 1.5 天 |
| P1 place-service | 实体与 Mapper、地点 CRUD、高德代理、坐标转换 | 接口可增删改查 | 2 天 |
| P2 media-service | 上传接口、方向纠正、WebP 与缩略图、MinIO 读写 | 照片可上传可访问 | 1.5 天 |
| P3 gateway | 路由规则、JWT 签发与校验、traceId 透传 | 统一入口可登录 | 1 天 |
| P4 stats-service | Feign 拉数据、省市聚合、Redis 缓存 | 点亮数据可返回 | 1 天 |
| P5 前端 | Vue 骨架、桌面三栏布局、登录、打卡表单、点亮视图、时间轴与详情 | 桌面浏览器可完整走一遍 | 5 天 |
| P6 联调 | 跨服务链路、错误码统一、孤儿照片对账任务 | 主流程无阻塞 | 1.5 天 |
| P7 本地化 | 启动脚本、healthcheck、备份脚本与恢复演练 | 开机一条命令可用 | 1 天 |

合计 8 个阶段 14.5 天；含 20% 缓冲后为 18 个工作日。

### 里程碑

| 里程碑 | 时间点 | 判定标准 |
| --- | --- | --- |
| M1 打通 | 第 5 天末 | 从网关进来能完成一次打卡，地点与照片都落库，服务间调用正常 |
| M2 点亮 | 第 8 天末 | 点亮视图上线，地图第一次亮起来 |
| M3 可用 | 第 14.5 天末 | 一条命令起停，重启电脑后数据无损，备份脚本跑通 |

## 10. 验收标准

| 序号 | 验收项 |
| --- | --- |
| 1 | 桌面 Chrome / Edge 打开 `http://localhost:5173`，1280 宽下三栏不塌、不出现横向滚动条，登录后进入地图首页 |
| 2 | 搜索"西湖"出现候选，选中后坐标与行政区自动填充；地图上单击也能选点，右键可取消 |
| 3 | 上传 3 张照片，详情右栏可点开全屏大图并用方向键切换，时间轴缩略图 1 秒内出现 |
| 4 | 保存后地图立即出现标记点，刷新后仍在；切换年份筛选列表即时变化且总数正确 |
| 5 | 点亮视图正确显示省市，颜色随记录数变化，统计数与左栏列表总数一致 |
| 6 | 删除地点后，照片对象在 7 天内被对账任务清理 |
| 7 | 导出 zip 解压后 JSON 可读、原图可打开 |
| 8 | 重启电脑后按 §8.3 一条命令拉起，四个服务全绿，数据无丢失；备份目录当天有产物 |

## 11. 风险与规避

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| 服务多、启动慢、依赖顺序错 | 首屏 500，排查耗时 | 中间件配 healthcheck；服务间用 Nacos 服务名 + 网关重试，不依赖启动顺序 |
| 跨服务删除产生孤儿照片 | 存储缓慢膨胀 | 软删 + 每日对账任务，孤儿对象超 7 天清理 |
| 链路长、日志分散 | 定位问题靠猜 | 网关生成 traceId 全链路透传，IDEA 里四个控制台按 traceId 搜 |
| 数据只在本机，硬盘故障即全丢 | 几年的记录一次性消失 | 每日 pg_dump + mc mirror 到本地盘与移动硬盘，月度恢复演练 |
| 范围膨胀（想再拆服务） | 永远上不了线 | 锁死四个服务，新需求先进 backlog，不在 MVP 内讨论 |
| 顺手去适配手机 | 布局双份维护，拖垮 P5 | 明确不做；窗口小于 1280 时给一句"请在电脑上使用"的提示即可 |
