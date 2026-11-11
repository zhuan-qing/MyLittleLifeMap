package com.hc.lifemap.media.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hc.lifemap.media.entity.Photo;
import org.apache.ibatis.annotations.Mapper;

/**
 * photo 单表 CRUD 由 {@link BaseMapper} 提供；按 place_id 的批量查询在 T07 用注解 SQL 补。
 *
 * <p>标 {@code @Mapper} 而不是在启动类上开 {@code @MapperScan}：把"我是 Mapper"留在文件里，
 * 新增 Mapper 不用回头改启动类。</p>
 */
@Mapper
public interface PhotoMapper extends BaseMapper<Photo> {
}
