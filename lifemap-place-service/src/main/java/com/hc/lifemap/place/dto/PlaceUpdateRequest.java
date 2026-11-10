package com.hc.lifemap.place.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 修改地点的入参（dev_doc §6.3 的 PUT 契约）：局部更新，{@code null} 表示该字段不动。
 *
 * <p>「不改」与「清空」靠 null / 空串区分：{@code null} = 没传，保持原值；{@code ""} = 主动清空，
 * 服务层归一成 null 落库。没有这个区分，前端就无法在不重传整个对象的情况下删掉一条笔记。</p>
 *
 * <p>不校验 {@code @NotBlank}（null 是合法输入），名称非空由服务层在"确实传了"时判断。</p>
 */
public record PlaceUpdateRequest(
        @Size(max = 200, message = "地点名称不能超过 200 字")
        String name,

        @DecimalMin(value = "-180", message = "经度必须在 -180 到 180 之间")
        @DecimalMax(value = "180", message = "经度必须在 -180 到 180 之间")
        BigDecimal lng,

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

        @PastOrPresent(message = "到访日期不能晚于今天")
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate visitedOn,

        @Min(value = 1, message = "评分只能是 1 到 5")
        @Max(value = 5, message = "评分只能是 1 到 5")
        Integer rating,

        @Size(max = 500, message = "笔记不能超过 500 字")
        String note) {
}
