package com.hc.lifemap.place.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hc.lifemap.place.entity.Place;
import org.apache.ibatis.annotations.Mapper;

/**
 * place 单表 CRUD 由 {@link BaseMapper} 提供，本接口不写 SQL；聚合查询（P4 前的 adcode 统计）
 * 再加注解 SQL。
 *
 * <p>标 {@code @Mapper} 而不是在启动类上开 {@code @MapperScan}：两者等效，但前者把"我是 Mapper"
 * 这个事实留在文件里，新增 Mapper 不用回头改启动类。</p>
 */
@Mapper
public interface PlaceMapper extends BaseMapper<Place> {
}
