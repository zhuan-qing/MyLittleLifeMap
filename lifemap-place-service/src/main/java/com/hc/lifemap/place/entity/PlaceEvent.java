package com.hc.lifemap.place.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * place_event 表映射：地点变更事件，供 media 侧对账清理孤儿照片。
 *
 * <p><b>故意不映射 {@code payload}</b>：它是 {@code jsonb}，本阶段应用侧既不写也不读；
 * 一旦映射了又给 null 插入，PG 会报 {@code column "payload" is of type jsonb but expression
 * is of type character varying}。表里给它 {@code DEFAULT '{}'}，不写也满足 NOT NULL。</p>
 */
@Data
@TableName("place_event")
public class PlaceEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long placeId;

    /** 当前只有 PLACE_DELETED。 */
    private String eventType;

    /** 0 待处理 / 1 已处理 / 2 处理失败。 */
    private Integer status;

    @TableField(insertStrategy = FieldStrategy.NEVER)
    private OffsetDateTime createdAt;

    private OffsetDateTime processedAt;
}
