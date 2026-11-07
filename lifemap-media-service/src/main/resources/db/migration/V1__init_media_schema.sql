-- =====================================================================
-- V1 基线：media_db（media 服务独占）
-- 口径来源：dev_doc.md §5.1 photo 表 + §7 规约③ 图片管线
--
-- 相对文档的一处补充：文档只列了 object_key（原图）与 thumb_key（缩略图），
-- 但规约③要求同时产出 2048px WebP（桌面详情大图），因此增加 webp_key。
-- 三个对象同桶不同前缀：originals/ webp/ thumbs/，均按 年/月/uuid 分片。
-- 原图永不修改、永不裁剪。
-- =====================================================================

CREATE TABLE photo
(
    id         BIGSERIAL PRIMARY KEY,
    place_id   BIGINT       NOT NULL,
    object_key VARCHAR(255) NOT NULL,
    webp_key   VARCHAR(255),
    thumb_key  VARCHAR(255) NOT NULL,
    width      INT          NOT NULL,
    height     INT          NOT NULL,
    bytes      BIGINT       NOT NULL,
    -- 拍摄时间取 EXIF DateTimeOriginal，缺失时回落为上传时间
    taken_at   TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 软删：先标记录，MinIO 对象由对账任务清理（超 7 天的孤儿才删）
    deleted_at TIMESTAMPTZ,

    CONSTRAINT uk_photo_object_key UNIQUE (object_key),
    CONSTRAINT ck_photo_size CHECK (width > 0 AND height > 0 AND bytes > 0)
);

COMMENT ON TABLE photo IS '照片元数据，物理文件在 MinIO';
COMMENT ON COLUMN photo.place_id IS '所属地点 ID，跨库引用，不建外键（数据归属原则）';
COMMENT ON COLUMN photo.object_key IS 'MinIO 原图 key，例：originals/2026/11/uuid.jpg';
COMMENT ON COLUMN photo.webp_key IS 'MinIO 2048px WebP key，桌面详情大图使用';
COMMENT ON COLUMN photo.thumb_key IS 'MinIO 480px 缩略图 key，时间轴与点亮网格使用';
COMMENT ON COLUMN photo.deleted_at IS '软删时间，NULL 表示有效';

-- 按地点取照片（详情页）与按地点批量对账都要它
CREATE INDEX idx_photo_place_id ON photo (place_id);
