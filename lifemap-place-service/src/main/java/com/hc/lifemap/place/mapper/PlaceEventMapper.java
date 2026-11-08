package com.hc.lifemap.place.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hc.lifemap.place.entity.PlaceEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * place_event 的单表 CRUD；删除事件的写入与对账消费分别在 P1-T09 与 P2 落地。
 */
@Mapper
public interface PlaceEventMapper extends BaseMapper<PlaceEvent> {
}
