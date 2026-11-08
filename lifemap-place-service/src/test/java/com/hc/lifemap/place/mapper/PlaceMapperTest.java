package com.hc.lifemap.place.mapper;

import com.hc.lifemap.place.entity.Place;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 只验证"能连、能读、类型对"三件事，不插入任何数据。
 *
 * <p>断言真实计数而不是"不抛异常"：{@code assertDoesNotThrow} 在 Mapper 根本没注入、
 * SQL 被静默跳过时也会绿，等于没测。这里拿返回值本身做判断。</p>
 *
 * <p>没有写成 {@code assertEquals(0, count)}：库是长期存在的数据卷，P1-T05 开始会真的写入地点，
 * 那时"必须为 0"会变成一条固定报红。改成"count 与 list.size() 一致"，空表和非空表都成立，
 * 且非空时顺带跑了一遍行映射（{@code NUMERIC} → BigDecimal、{@code TIMESTAMPTZ} → OffsetDateTime）。</p>
 */
@SpringBootTest
class PlaceMapperTest {

    @Autowired
    private PlaceMapper placeMapper;

    @Test
    void placeTableIsReadable() {
        Long total = placeMapper.selectCount(null);
        assertNotNull(total, "count 应返回真实数字，返回 null 说明 SQL 没执行");

        List<Place> rows = placeMapper.selectList(null);
        assertNotNull(rows, "selectList 不应返回 null");
        assertEquals(total.longValue(), rows.size(), "count 与 list 的行数必须一致");
        assertTrue(total >= 0);
    }
}
