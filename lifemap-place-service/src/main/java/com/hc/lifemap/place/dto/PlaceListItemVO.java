package com.hc.lifemap.place.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.hc.lifemap.place.entity.Place;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 左栏一行：只带渲染列表真正需要的字段。
 *
 * <p>不是 {@code Place} 的子集化，而是刻意裁剪——{@code deletedAt}、{@code createdAt}、
 * {@code lngWgs/latWgs} 对列表页没有意义，多带一个字段就多一份被前端误用的可能。</p>
 *
 * <p>{@code thumbUrl} 在 P2 落地前恒为 {@code null}，字段先留在契约里：
 * 前端按最终结构写一次渲染逻辑，P2 补上取值时不必再改前端。</p>
 *
 * <p>坐标用 {@link BigDecimal} 而非 double：列表页的「同地点判定」也靠这两个值，
 * double 序列化后末位会飘。</p>
 *
 * @param visitedOn 到访日期，{@code yyyy-MM-dd}。不标 {@code @JsonFormat} 时 Boot 默认也输出该格式，
 *                  但显式标出来是为了改全局 Jackson 配置时不至于静默变成时间戳
 */
public record PlaceListItemVO(
        Long id,
        String name,
        BigDecimal lng,
        BigDecimal lat,
        String adcode,
        String province,
        String city,
        @JsonFormat(pattern = "yyyy-MM-dd") LocalDate visitedOn,
        Integer rating,
        String note,
        String thumbUrl) {

    /** 实体 → 列表行；{@code thumbUrl} 在此固定给 null，等 P2 接照片表。 */
    public static PlaceListItemVO from(Place place) {
        return new PlaceListItemVO(
                place.getId(),
                place.getName(),
                place.getLng(),
                place.getLat(),
                place.getAdcode(),
                place.getProvince(),
                place.getCity(),
                place.getVisitedOn(),
                place.getRating(),
                place.getNote(),
                null);
    }
}
