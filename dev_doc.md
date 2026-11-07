# 个人足迹地图 · 开发实施方案

**文档版本**：v1.0（开发版）　**编制日期**：2026 年 11 月 7 日　**前置文档**：《个人足迹地图产品调研与 MVP 实施方案》v2.0

**读者与用途**：本文面向开发人员，是开发期间的唯一实施依据。产品层面的"为什么这样取舍"见前置文档，本文只规定"怎么做"——技术栈、服务边界、数据模型、接口契约、实现规约、部署拓扑与验收标准。

## 1. 全局约束

以下约束贯穿全部开发工作，任何设计决策不得与之冲突。

| 约束项 | 规定 |
| --- | --- |
| 服务拆分 | 网关 + 地点 + 媒体 + 统计，共 4 个业务服务，锁死不再增加；新需求一律进 backlog |
| 部署形态 | 单机 Docker Compose，共 9 个容器，一条命令起停 |
| 数据归属 | 每个服务只写自己的库，跨服务读数据走 API，禁止跨库 JOIN |
| 鉴权 | JWT 只在网关校验一次，向下游透传 X-User-Id 与 traceId；服务间内网直连，不重复鉴权，不对外暴露端口 |
| 一致性 | 不引入分布式事务（不用 Seata）；删除走软删 + 事件表，media 定时对账清理孤儿照片，允许分钟级不一致 |
| 点亮口径 | 点亮是查询结果，不是状态，不建点亮表 |

## 2. 功能范围

### 2.1 需求清单

| 编号 | 需求 | 优先级 | MVP |
| --- | --- | --- | --- |
| R01 | 搜索地点添加，自动带出坐标与行政区 | P0 | 包含 |
| R02 | 地图点击 / 长按选点添加 | P0 | 包含 |
| R03 | 填写到访日期、1–5 星评分、一句话笔记 | P0 | 包含 |
| R04 | 单条记录上传多张照片 | P0 | 包含 |
| R05 | 省 / 市两级点亮视图，按到访数着色 | P0 | 包含 |
| R06 | 统计卡：已点亮省份、城市、地点总数 | P0 | 包含 |
| R07 | 地点列表，按时间倒序，支持年份筛选 | P0 | 包含 |
| R08 | 详情页：照片轮播、笔记、地图定位 | P0 | 包含 |
| R09 | 单用户登录（JWT） | P0 | 包含 |
| R10 | 全量导出：JSON + 原图打包 | P0 | 包含 |
| R11 | 批量导入相册（EXIF 自动定位） | P1 | 缓做 |
| R12 | 足迹海报生成 | P1 | 缓做 |
| R13 | 按省份聚合浏览 + 关键词搜索 | P1 | 缓做 |
| R14 | 轨迹线记录与回放 | P2 | 不做 |
| R15 | 同行人、心愿清单 | P2 | 不做 |

### 2.2 范围说明

- **MVP 包含**：R01–R10，覆盖打卡、照片、点评、点亮、列表详情、登录、导出的完整闭环。
- **明确不做**：轨迹线、批量导入、分享海报、评论点赞、多用户、区县级点亮、原生 App 安装包、离线地图。
- **R11 缓做理由**：批量导入依赖 EXIF 解析、批量逆地理编码、草稿审核三个子系统，等于再造半个产品；先用"手动补 10 条"验证交互，再决定是否投入。

## 3. 技术栈

| 层次 | 选型 | 说明 |
| --- | --- | --- |
| 运行时 | JDK 21 + Maven 3.9（Wrapper 锁定） | Spring Boot 3.3 基线 |
| 微服务框架 | Spring Boot 3.3 + Spring Cloud 2023.0.x | Gateway / OpenFeign / LoadBalancer |
| 注册与配置 | Nacos 2.3 | 注册中心与配置中心同一实例；namespace 按环境隔离，group 按服务拆分 |
| 持久层 | MyBatis-Plus 3.5 | BaseMapper 免写单表 CRUD，聚合用注解 SQL |
| 数据库 | PostgreSQL 18.6，一实例三库 | 每个服务独立 database |
| 缓存 | Redis 7 | 统计结果缓存、登录态 |
| 对象存储 | MinIO | 照片与缩略图，按 年/月 分前缀 |
| 数据迁移 | Flyway | 每个服务管理自己的 migration |
| 前端 | Vue 3 + Vite + Vant | 单体 SPA，直接调网关，不做 BFF |
| 地图与图表 | 高德 JS API 2.0 + ECharts 5 | 底图自带审图号，点亮版图用行政区 GeoJSON |
| 部署 | Caddy + Docker Compose | 单机九容器 |

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
| 入口 | 浏览器 → Caddy（HTTPS，443）→ gateway（JWT 校验，生成 traceId） |
| /api/places/** | gateway → place-service → PostgreSQL(place_db)；地理接口 → 高德 Web 服务（POI / 逆地理） |
| /api/photos/** | gateway → media-service → MinIO + PostgreSQL(media_db) |
| /api/stats/** | gateway → stats-service →（OpenFeign）→ place-service 内部接口 → Redis（统计结果缓存 5 分钟） |
| 服务注册 | 所有服务启动时向 Nacos 注册，网关按服务名负载均衡 |

### 4.3 编码硬约定

1. **数据归属**：stats 需要 adcode 时调 place 的内部接口，不直连 place_db。
2. **鉴权与链路**：JWT 仅网关校验；网关为每个请求生成 traceId 并全链路透传，日志按 traceId 聚合。
3. **一致性**：删除地点用软删 + 事件表；media 的定时对账任务清理孤儿照片；单用户场景不做分布式事务。

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

**photo（media_db）**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | BIGSERIAL | 主键 |
| place_id | BIGINT | 所属地点 |
| object_key | VARCHAR | MinIO 原图 key |
| thumb_key | VARCHAR | MinIO 缩略图 key |
| width / height | INT | 尺寸 |
| bytes | BIGINT | 文件大小 |
| taken_at | TIMESTAMPTZ | 拍摄时间 |

**app_setting（place_db）**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| key | VARCHAR(50) PK | 配置键（密码哈希、地图 Key 等） |
| value | TEXT | 配置值 |
| updated_at | TIMESTAMPTZ | 更新时间 |

### 5.2 索引设计

仅两个索引：place(visited_on desc) 支撑时间轴翻页；place(adcode) 支撑点亮聚合。不建"点亮表"——点亮是查询结果，不是状态。

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

- 时间统一 ISO-8601；列表接口统一分页参数；错误码全服务统一，联调阶段（P6）完成对齐。

## 7. 关键实现规约

**① 坐标转换（收在 place-service）**：照片 EXIF 是 WGS-84，高德底图是 GCJ-02，国内相差 300–800 米。入库前统一转 GCJ-02，同时保留 WGS-84 原值以便换底图。

**② 点亮不落库（stats-service）**：从 place 拉 adcode 列表，用 left(adcode,2) 拼接 0000 聚合省份、left(adcode,4) 拼接 00 聚合城市，结果放 Redis 缓存 5 分钟。改判定口径只改聚合 SQL 一处。

**③ 图片管线一次做对（media-service）**：校验类型与大小 → 按 EXIF 纠正方向 → 生成 1600px WebP（详情页）与 400px 缩略图（列表）→ 原图按 年/月/uuid 存入 MinIO。原图永不修改。

**④ 软删与对账**：删除地点写软删标记 + 事件表；media 每日对账任务清理孤儿照片，孤儿对象超过 7 天自动删除。

## 8. 部署

### 8.1 容器与资源

| 容器 | 作用 | 内存上限 |
| --- | --- | --- |
| caddy | HTTPS 与静态资源 | 64MB |
| gateway | 路由与鉴权 | 384MB |
| place-service | 地点与地理编码 | 512MB |
| media-service | 图片处理 | 512MB |
| stats-service | 点亮聚合 | 384MB |
| postgres | 数据库 | 768MB |
| redis | 缓存 | 128MB |
| minio | 对象存储 | 384MB |
| nacos | 注册中心 | 512MB |

合计常驻约 3.6GB，按实际服务器规格分配。全部容器配 healthcheck，应用容器用 service_healthy 声明启动依赖；网关加重试与熔断。

### 8.2 Nacos 配置规约

单机模式即可。服务注册到同一 namespace；配置按 group 拆分（gateway / place / media / stats），改数据库连接串不用重新打镜像。配置变更走 Nacos 控制台，服务侧加 @RefreshScope 热更新。

### 8.3 备份

每日 03:00：pg_dump 三个库 + mc mirror 同步 MinIO 桶 + 导出 Nacos 配置，打包推到对象存储或 NAS，保留 30 天。每月在测试库跑一次 pg_restore 恢复演练。

## 9. 开发排期

| 阶段 | 任务 | 交付物 | 工期 |
| --- | --- | --- | --- |
| P0 基础设施 | 父 POM 与 Wrapper、compose 起 PG/Redis/MinIO/Nacos、Nacos 建 namespace 与四个 group、三个空库与 Flyway 基线 | 中间件全部可连通 | 1.5 天 |
| P1 place-service | 实体与 Mapper、地点 CRUD、高德代理、坐标转换 | 接口可增删改查 | 2 天 |
| P2 media-service | 上传接口、方向纠正、WebP 与缩略图、MinIO 读写 | 照片可上传可访问 | 1.5 天 |
| P3 gateway | 路由规则、JWT 签发与校验、traceId 透传 | 统一入口可登录 | 1 天 |
| P4 stats-service | Feign 拉数据、省市聚合、Redis 缓存 | 点亮数据可返回 | 1 天 |
| P5 前端 | Vue 骨架、登录、打卡页、点亮视图、列表与时间轴 | 手机上可完整走一遍 | 5 天 |
| P6 联调 | 跨服务链路、错误码统一、孤儿照片对账任务 | 主流程无阻塞 | 1.5 天 |
| P7 部署验收 | 镜像构建、compose 编排、healthcheck、备份脚本 | 可日常使用 | 1.5 天 |

合计 8 个阶段 15 天；含 20% 缓冲后为 18 个工作日。

### 里程碑

| 里程碑 | 时间点 | 判定标准 |
| --- | --- | --- |
| M1 打通 | 第 5 天末 | 从网关进来能完成一次打卡，地点与照片都落库，服务间调用正常 |
| M2 点亮 | 第 8 天末 | 点亮视图上线，地图第一次亮起来 |
| M3 上线 | 第 15 天末 | 九个容器按依赖顺序启动，重启后数据无损 |

## 10. 验收标准

| 序号 | 验收项 |
| --- | --- |
| 1 | 手机浏览器打开网址，登录后进入地图首页，可添加到主屏幕 |
| 2 | 搜索"西湖"出现候选，选中后坐标与行政区自动填充 |
| 3 | 上传 3 张照片，详情页可滑动查看，缩略图 1 秒内出现 |
| 4 | 保存后地图立即出现标记点，刷新后仍在 |
| 5 | 点亮视图正确显示省市，颜色随记录数变化，统计数与列表一致 |
| 6 | 删除地点后，照片对象在 7 天内被对账任务清理 |
| 7 | 导出 zip 解压后 JSON 可读、原图可打开 |
| 8 | 服务器重启后九个容器按依赖顺序拉起，数据无丢失 |

## 11. 风险与规避

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| 服务多、启动慢、依赖顺序错 | 首屏 500，排查耗时 | 全部配 healthcheck，service_healthy 依赖；网关加重试与熔断 |
| 跨服务删除产生孤儿照片 | 存储缓慢膨胀 | 软删 + 每日对账任务，孤儿对象超 7 天清理 |
| 链路长、日志分散 | 定位问题靠猜 | 网关生成 traceId 全链路透传，日志按 traceId 聚合 |
| 范围膨胀（想再拆服务） | 永远上不了线 | 锁死四个服务，新需求先进 backlog，不在 MVP 内讨论 |
