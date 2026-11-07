-- =====================================================================
-- V1 基线：place_db（place 服务独占）
-- 口径来源：dev_doc.md §5 数据模型
-- 命名规约：字段 snake_case；时间一律 TIMESTAMPTZ；坐标一律 NUMERIC(10,6)
-- =====================================================================

-- ---------------------------------------------------------------------
-- place：一条到访记录
-- 坐标存两套：lng/lat 为 GCJ-02（高德底图用），lng_wgs/lat_wgs 为 WGS-84
-- （EXIF 原值，换底图时不用重新采集）。国内两者差 300–800 米，必须分开存。
-- ---------------------------------------------------------------------
CREATE TABLE place
(
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    lng        NUMERIC(10, 6) NOT NULL,
    lat        NUMERIC(10, 6) NOT NULL,
    lng_wgs    NUMERIC(10, 6),
    lat_wgs    NUMERIC(10, 6),
    adcode     CHAR(6),
    province   VARCHAR(64),
    city       VARCHAR(64),
    visited_on DATE         NOT NULL,
    rating     SMALLINT,
    note       TEXT,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 软删：删除地点只打标记 + 写事件表，物理清理交给 media 的每日对账任务
    deleted_at TIMESTAMPTZ,

    CONSTRAINT ck_place_lng CHECK (lng BETWEEN -180 AND 180),
    CONSTRAINT ck_place_lat CHECK (lat BETWEEN -90 AND 90),
    CONSTRAINT ck_place_adcode CHECK (adcode IS NULL OR adcode ~ '^[0-9]{6}$'),
    CONSTRAINT ck_place_rating CHECK (rating IS NULL OR rating BETWEEN 1 AND 5)
);

COMMENT ON TABLE place IS '到访记录（地图上的一个点）';
COMMENT ON COLUMN place.lng IS '经度，GCJ-02 火星坐标，高德底图直接使用';
COMMENT ON COLUMN place.lat IS '纬度，GCJ-02';
COMMENT ON COLUMN place.lng_wgs IS '经度，WGS-84 原始值（EXIF/点击取点）';
COMMENT ON COLUMN place.lat_wgs IS '纬度，WGS-84 原始值';
COMMENT ON COLUMN place.adcode IS '6 位行政区划代码，省市点亮完全由它推导';
COMMENT ON COLUMN place.deleted_at IS '软删时间，NULL 表示有效';

-- 全库只为 place 建两个索引，与 dev_doc §5.2 一致：
-- 1) 时间轴倒序翻页
CREATE INDEX idx_place_visited_on ON place (visited_on DESC);
-- 2) 省市点亮聚合（group by left(adcode,2) / left(adcode,4)）
CREATE INDEX idx_place_adcode ON place (adcode);


-- ---------------------------------------------------------------------
-- place_event：删除事件表。media 侧据此核对哪些 place_id 已经不在了，
-- 从而清理孤儿照片。单用户场景不上分布式事务，用事件 + 对账兜住分钟级不一致。
-- ---------------------------------------------------------------------
CREATE TABLE place_event
(
    id           BIGSERIAL PRIMARY KEY,
    place_id     BIGINT       NOT NULL,
    event_type   VARCHAR(32)  NOT NULL,
    payload      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    -- 0=待处理 1=已处理 2=处理失败
    status       SMALLINT     NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ,

    CONSTRAINT ck_place_event_status CHECK (status IN (0, 1, 2))
);

COMMENT ON TABLE place_event IS '地点变更事件，当前只有 PLACE_DELETED，供 media 对账消费';
COMMENT ON COLUMN place_event.status IS '0 待处理 / 1 已处理 / 2 处理失败';

CREATE INDEX idx_place_event_pending ON place_event (status, created_at);


-- ---------------------------------------------------------------------
-- app_setting：单用户配置（登录密码哈希、地图 Key 等）。
-- 单用户产品，不为账号单独建表，配置一律键值化。
-- ---------------------------------------------------------------------
CREATE TABLE app_setting
(
    key        VARCHAR(50) PRIMARY KEY,
    value      TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE app_setting IS '应用键值配置：password_hash、amap_key 等';
