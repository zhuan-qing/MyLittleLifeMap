package com.hc.lifemap.place.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.hc.lifemap.place.entity.Place;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 单个地点的完整出参：新增、详情、修改共用。
 *
 * <p>与 {@link PlaceListItemVO} 的差别是"给人看详情"：带两套坐标（换底图、核对 EXIF 要看）
 * 与时间戳（详情页要显示"何时记的"）。{@code deletedAt} 不出现——软删的记录接口直接给 10404。</p>
 *
 * <p>时间戳显式标 {@code @JsonFormat}：一旦有人打开全局
 * {@code write-dates-as-timestamps}，不加注解就会静默变成一串数字。</p>
 */
public record PlaceVO(
        Long id,
        String name,
        BigDecimal lng,
        BigDecimal lat,
        BigDecimal lngWgs,
        BigDecimal latWgs,
        String adcode,
        String province,
        String city,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate visitedOn,
        Integer rating,
        String note,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX") OffsetDateTime createdAt,
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX") OffsetDateTime updatedAt) {

    public static PlaceVO from(Place place) {
        return new PlaceVO(
                place.getId(),
                place.getName(),
                place.getLng(),
                place.getLat(),
                place.getLngWgs(),
                place.getLatWgs(),
                place.getAdcode(),
                place.getProvince(),
                place.getCity(),
                place.getVisitedOn(),
                place.getRating(),
                place.getNote(),
                place.getCreatedAt(),
                place.getUpdatedAt());
    }
}
