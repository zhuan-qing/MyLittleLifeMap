package com.hc.lifemap.place.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 新增地点的入参（dev_doc §6.3 的 POST 契约）。
 *
 * <p>校验放在这里而不是 service：这三类错误（缺字段、越界、日期在未来）是前端表单能直接
 * 标红的，越早失败越好；{@code @Valid} 只在 {@code @RequestBody} 参数上生效，
 * 方法级别聚合由 {@code GlobalExceptionHandler} 负责拼成 10400。</p>
 *
 * <p>坐标一律 {@link BigDecimal}：NUMERIC(10,6) 走 double 会在末位丢精度，序列化/反序列化
 * 各抖一次就够让地图上的点偏出几十米。</p>
 *
 * <p>坐标为两套：{@code lng/lat} 是 GCJ-02（地图点击取点），{@code lngWgs/latWgs} 是
 * WGS-84（照片 EXIF 原值）。两套都可以给，由 {@code PlaceCommandService} 判定以哪套为
 * 真值并补算另一套；这里不做互斥校验——同时从地图和照片取点本来就是合法输入。</p>
 *
 * @param lngWgs 可空，但必须与 {@code latWgs} 成对出现，只给一个在 service 层报 10400
 * @param adcode 允许空串（表单没选行政区时会传 {@code ""}），service 会归一成 null；
 *               正则写成 {@code ^$|\d{6}} 就是为了让空串先过校验，别在库里撞 CHECK 约束
 */
public record PlaceCreateRequest(
        @NotBlank(message = "地点名称不能为空")
        @Size(max = 200, message = "地点名称不能超过 200 字")
        String name,

        @NotNull(message = "经度不能为空")
        @DecimalMin(value = "-180", message = "经度必须在 -180 到 180 之间")
        @DecimalMax(value = "180", message = "经度必须在 -180 到 180 之间")
        BigDecimal lng,

        @NotNull(message = "纬度不能为空")
        @DecimalMin(value = "-90", message = "纬度必须在 -90 到 90 之间")
        @DecimalMax(value = "90", message = "纬度必须在 -90 到 90 之间")
        BigDecimal lat,

        @DecimalMin(value = "-180", message = "WGS 经度必须在 -180 到 180 之间")
        @DecimalMax(value = "180", message = "WGS 经度必须在 -180 到 180 之间")
        BigDecimal lngWgs,

        @DecimalMin(value = "-90", message = "WGS 纬度必须在 -90 到 90 之间")
        @DecimalMax(value = "90", message = "WGS 纬度必须在 -90 到 90 之间")
        BigDecimal latWgs,

        @Pattern(regexp = "^$|\\d{6}", message = "行政区划代码必须是 6 位数字")
        String adcode,

        @Size(max = 64, message = "省份名过长")
        String province,

        @Size(max = 64, message = "城市名过长")
        String city,

        @NotNull(message = "到访日期不能为空")
        @PastOrPresent(message = "到访日期不能晚于今天")
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate visitedOn,

        @Min(value = 1, message = "评分只能是 1 到 5")
        @Max(value = 5, message = "评分只能是 1 到 5")
        Integer rating,

        @Size(max = 500, message = "笔记不能超过 500 字")
        String note) {
}
