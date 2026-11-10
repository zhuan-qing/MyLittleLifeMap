package com.hc.lifemap.place.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.hc.lifemap.place.entity.Place;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 左栏一行：只带渲染列表真正需要的字段。
 *
 * <p>不是把实体字段原样透出，而是按列表需要裁剪：{@code deletedAt}、时间戳、两套坐标
 * 对列表页没有意义，多带一个字段就多一份被前端误用的可能。</p>
 *
 * <p>{@code thumbUrl} 在 P2 落地前恒为 {@code null}，字段先留在契约里：
 * 前端按最终结构写一次渲染逻辑，P2 补上取值时不必再改前端。</p>
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
