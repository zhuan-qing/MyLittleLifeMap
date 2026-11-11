package com.hc.lifemap.media.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * photo 表映射：一张照片的元数据，物理文件在对象存储。
 *
 * <p>三列各管一件事：{@code object_key} 原图（永不修改）、{@code webp_key} 2048px 详情大图、
 * {@code thumb_key} 480px 缩略图；三个对象同桶不同前缀、共用同一个 uuid 基名。</p>
 *
 * <p>{@code bytes} 存的是**原图**大小，不是派生图——它要回答"这条记录占了多少磁盘"。
 * 时间统一用 {@link OffsetDateTime}：{@code LocalDateTime} 会被 JDBC 按本地时区转一次，漂 8 小时。</p>
 *
 * <p>{@code created_at} 标 {@code insertStrategy = NEVER}，插入时不带该列，让 PG 的
 * {@code DEFAULT now()} 生效——否则 MP 会把 Java 侧的 null 写进去，撞 NOT NULL。</p>
 */
@Data
@TableName("photo")
public class Photo {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 跨库引用 place 的地点 ID，不建外键（数据归属原则）。 */
    private Long placeId;

    private String objectKey;

    private String webpKey;

    private String thumbKey;

    private Integer width;

    private Integer height;

    /** 原图字节数。 */
    private Long bytes;

    /** 拍摄时间，取 EXIF DateTimeOriginal；缺失时回落为上传时间。 */
    private OffsetDateTime takenAt;

    @TableField(insertStrategy = FieldStrategy.NEVER)
    private OffsetDateTime createdAt;

    /** 软删标记，非空即不可见；对象由对账任务清理。 */
    private OffsetDateTime deletedAt;
}
