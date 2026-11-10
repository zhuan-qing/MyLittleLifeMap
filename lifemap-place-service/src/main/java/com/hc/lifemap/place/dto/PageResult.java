package com.hc.lifemap.place.dto;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.function.Function;

/**
 * 分页壳：前端只认这五个字段，结构不随 ORM 变化。
 *
 * <p>不直接返回 MP 的 {@code Page}：它会把 {@code orders}、{@code optimizeCountSql} 这些内部
 * 开关一并序列化出去，前端拿到的是实现细节而不是契约；P4 的统计接口还要复用同一个壳。</p>
 *
 * @param list 当前页数据，空页返回空数组而不是 null（前端少一个判空分支）
 */
public record PageResult<T>(
        List<T> list,
        long total,
        long page,
        long size,
        long pages) {

    /** 分页结果 → 分页壳，顺带把实体映射成 VO，免得调用方各写一遍 stream。 */
    public static <E, T> PageResult<T> of(IPage<E> source, Function<E, T> mapper) {
        return new PageResult<>(
                source.getRecords().stream().map(mapper).toList(),
                source.getTotal(),
                source.getCurrent(),
                source.getSize(),
                source.getPages());
    }
}
