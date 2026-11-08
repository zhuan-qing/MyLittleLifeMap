package com.hc.lifemap.place.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * place 表映射：一条到访记录。
 *
 * <p>三个类型选择在这里一次定死，后面不再改（dev_doc §5.4）：</p>
 * <ul>
 *   <li>坐标 {@code NUMERIC(10,6)} → {@link BigDecimal}。换 {@code double} 会丢末位，
 *       0.000001 度约 0.1 米，单条看不出，聚合与去重会出问题。</li>
 *   <li>{@code DATE} → {@link LocalDate}，到访日期没有时区概念。</li>
 *   <li>{@code TIMESTAMPTZ} → {@link OffsetDateTime}；用 {@code LocalDateTime} 会被 JDBC
 *       按时区转换一次，时间漂 8 小时。</li>
 * </ul>
 *
 * <p>{@code created_at / updated_at} 标 {@code insertStrategy = NEVER}：插入时不带这两列，
 * 让 PG 的 {@code DEFAULT now()} 生效。不标的话 MP 会把 Java 侧的 null 写进去，直接违反 NOT NULL。</p>
 */
@Data
@TableName("place")
public class Place {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 经度，GCJ-02，高德底图直接使用。 */
    private BigDecimal lng;

    /** 纬度，GCJ-02。 */
    private BigDecimal lat;

    /** 经度，WGS-84 原始值（EXIF / 点击取点），换底图时才用。 */
    private BigDecimal lngWgs;

    /** 纬度，WGS-84 原始值。 */
    private BigDecimal latWgs;

    /** 6 位行政区划代码，省市点亮完全由它推导。 */
    private String adcode;

    private String province;

    private String city;

    private LocalDate visitedOn;

    /** 1–5 星，库里有 CHECK 约束。 */
    private Integer rating;

    private String note;

    @TableField(insertStrategy = FieldStrategy.NEVER)
    private OffsetDateTime createdAt;

    @TableField(insertStrategy = FieldStrategy.NEVER)
    private OffsetDateTime updatedAt;

    /** 软删时间，null 表示有效。查询由服务层显式带 is null 条件，不用 {@code @TableLogic}。 */
    private OffsetDateTime deletedAt;
}
