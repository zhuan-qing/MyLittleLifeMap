package com.hc.lifemap.place.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hc.lifemap.place.entity.Place;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * place 单表 CRUD 由 {@link BaseMapper} 提供；只有 T12 的 adcode 去重查询用注解 SQL
 * （为一条查询建 XML 不划算）。
 *
 * <p>标 {@code @Mapper} 而不是在启动类上开 {@code @MapperScan}：两者等效，但前者把"我是 Mapper"
 * 这个事实留在文件里，新增 Mapper 不用回头改启动类。</p>
 */
@Mapper
public interface PlaceMapper extends BaseMapper<Place> {

    /**
     * 有效记录里去重后的 adcode，按字典序翻页。
     *
     * <p>排除软删与 null：软删的地点不该继续为省市点亮贡献，没有 adcode 的记录也参与不了聚合。</p>
     */
    @Select("""
            SELECT DISTINCT adcode FROM place
            WHERE deleted_at IS NULL AND adcode IS NOT NULL
            ORDER BY adcode
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<String> selectDistinctAdcodes(@Param("limit") int limit, @Param("offset") int offset);

    /** 去重后的总数，供调用方判断还能不能继续翻页。 */
    @Select("SELECT count(DISTINCT adcode) FROM place WHERE deleted_at IS NULL AND adcode IS NOT NULL")
    long countDistinctAdcodes();
}
