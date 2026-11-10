package com.hc.lifemap.place.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.hc.lifemap.place.entity.Place;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 单个地点的完整出参：新增与详情（P1-T08）共用一份结构。
 *
 * <p>与 {@link PlaceListItemVO} 的差别是"给人看详情"而非"给列表渲染一行"：
 * 带 {@code lngWgs/latWgs}（换底图、核对 EXIF 时要看）、带 {@code createdAt/updatedAt}
 * （详情页要显示"何时记的"）。{@code deletedAt} 不出现：软删的记录查不到，接口直接给 10404。</p>
 *
 * <p>时间戳显式标 {@code @JsonFormat}：契约要求"带时区偏移"，一旦有人把全局
 * {@code write-dates-as-timestamps} 打开，不加注解就会静默变成一串数字，前端
 * {@code new Date(...)} 还能跑，但和列表页的日期格式会对不上。</p>
 *
 * @param lngWgs WGS-84 经度，入库时由坐标转换补算（P1-T07），新增/详情返回时必然有值
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

    /** 实体 → 出参。{@code place} 必须来自一次真实查询，否则时间戳字段是 null。 */
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
