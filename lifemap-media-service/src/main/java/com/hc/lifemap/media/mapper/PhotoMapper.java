package com.hc.lifemap.media.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hc.lifemap.media.entity.Photo;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/**
 * photo 单表 CRUD 由 {@link BaseMapper} 提供；按 place_id 的批量查询在 T07 用注解 SQL 补。
 *
 * <p>标 {@code @Mapper} 而不是在启动类上开 {@code @MapperScan}：把"我是 Mapper"留在文件里，
 * 新增 Mapper 不用回头改启动类。</p>
 */
@Mapper
public interface PhotoMapper extends BaseMapper<Photo> {

    /**
     * 按地点批量取有效照片（T07 的内部接口用），一次 IN 查询，不让调用方按地点循环。
     *
     * <p>用默认方法而不是 XML/注解 SQL：条件都是单表等值与 IN，交给条件构造器更不容易写错，
     * 也不必为一条查询多维护一个映射文件。排序固定 place_id + id，保证前端拿到的顺序稳定。</p>
     */
    default List<Photo> selectByPlaceIds(Collection<Long> placeIds) {
        return selectList(Wrappers.<Photo>lambdaQuery()
                .in(Photo::getPlaceId, placeIds)
                .isNull(Photo::getDeletedAt)
                .orderByAsc(Photo::getPlaceId)
                .orderByAsc(Photo::getId));
    }
}
