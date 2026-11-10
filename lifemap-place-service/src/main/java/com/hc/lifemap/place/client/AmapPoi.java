package com.hc.lifemap.place.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;

/**
 * 一个 POI 候选，同时就是 {@code /api/geo/search} 的响应元素——前端选中后直接把
 * 名称、坐标、省市区、adcode 回填进表单，不必再发一次请求。
 *
 * @param lng 高德返回的 {@code location} 是 {@code "lng,lat"} 一个字符串，映射时已拆开；
 *            解析不出来就是 null，不抛异常（候选列表不该因为某条脏数据整批失败）
 */
public record AmapPoi(
        String id,
        String name,
        BigDecimal lng,
        BigDecimal lat,
        String province,
        String city,
        String adcode,
        String address) {

    static AmapPoi from(JsonNode node) {
        String location = text(node, "location");
        String[] parts = location == null ? new String[0] : location.split(",");
        boolean hasPair = parts.length == 2;

        return new AmapPoi(
                text(node, "id"),
                text(node, "name"),
                hasPair ? decimal(parts[0]) : null,
                hasPair ? decimal(parts[1]) : null,
                text(node, "pname"),
                text(node, "cityname"),
                text(node, "adcode"),
                text(node, "address"));
    }

    /**
     * 取字符串字段；空数组、缺字段、空串一律当 null。
     *
     * <p>高德把"没有值"写成空数组 {@code []} 而不是空串（直辖市的 {@code cityname}、
     * 无门牌时的 {@code address} 都这样），直接 {@code asText()} 会拿到 {@code "[]"} 甚至抛错。
     * 这里统一归一成 null，前端只需处理"有值 / 没值"两种情况。</p>
     */
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isValueNode()) {
            return null;
        }
        String raw = value.asText().trim();
        return raw.isEmpty() ? null : raw;
    }

    private static BigDecimal decimal(String raw) {
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
