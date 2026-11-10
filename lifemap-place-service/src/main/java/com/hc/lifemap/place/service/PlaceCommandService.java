package com.hc.lifemap.place.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hc.lifemap.common.api.ErrorCode;
import com.hc.lifemap.place.constant.PlaceEventType;
import com.hc.lifemap.place.dto.PlaceCreateRequest;
import com.hc.lifemap.place.dto.PlaceUpdateRequest;
import com.hc.lifemap.place.dto.PlaceVO;
import com.hc.lifemap.place.entity.Place;
import com.hc.lifemap.place.entity.PlaceEvent;
import com.hc.lifemap.place.exception.BizException;
import com.hc.lifemap.place.mapper.PlaceEventMapper;
import com.hc.lifemap.place.mapper.PlaceMapper;
import com.hc.lifemap.place.util.CoordinateConverter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

/**
 * 地点写入侧：新增、局部更新、软删（T09）。
 *
 * <p>与 {@link PlaceQueryService} 分开是因为两侧的失败语义不同：读侧几乎都是"参数不合法"，
 * 写侧还有"目标不存在"和"事务回滚"。混在一个类里迟早会出现"查询方法顺手写了一条"。</p>
 */
@Service
public class PlaceCommandService {

    private final PlaceMapper placeMapper;
    private final PlaceEventMapper placeEventMapper;

    public PlaceCommandService(PlaceMapper placeMapper, PlaceEventMapper placeEventMapper) {
        this.placeMapper = placeMapper;
        this.placeEventMapper = placeEventMapper;
    }

    /**
     * 新增一条到访记录，返回落库后的完整对象。
     *
     * <p>insert 之后回查一次：{@code created_at} 由 PG 的 {@code DEFAULT now()} 生成、
     * 实体上是 {@code insertStrategy = NEVER}，插入后内存里仍是 null，而契约要求返回它。
     * 宁可多一次主键查询，也不在 Java 侧自己造时间——两套时钟一定会对不上。</p>
     */
    public PlaceVO create(PlaceCreateRequest request) {
        Place place = new Place();
        place.setName(requireName(request.name()));

        Coords coords = resolveCoordinates(request.lng(), request.lat(), request.lngWgs(), request.latWgs());
        place.setLng(coords.lng());
        place.setLat(coords.lat());
        place.setLngWgs(coords.lngWgs());
        place.setLatWgs(coords.latWgs());

        place.setAdcode(blankToNull(request.adcode()));
        place.setProvince(blankToNull(request.province()));
        place.setCity(blankToNull(request.city()));
        place.setVisitedOn(request.visitedOn());
        place.setRating(request.rating());
        place.setNote(blankToNull(request.note()));

        placeMapper.insert(place);
        return PlaceVO.from(placeMapper.selectById(place.getId()));
    }

    /**
     * 局部更新：只写请求里出现过的字段。
     *
     * <p>用 {@link LambdaUpdateWrapper} 逐个 {@code set}，而不是 {@code updateById(实体)}——
     * 后者会把实体上的 null 一并写进库（请求没传的字段就被清空了），要绕开它只能塞一个半满的
     * 实体进去，还会覆盖期间别人改过的列。字段一个都没传时直接返回当前值，不产生一次只有
     * {@code updated_at} 变化的空写。</p>
     */
    public PlaceVO update(long id, PlaceUpdateRequest request) {
        Place existing = requireActive(id);

        LambdaUpdateWrapper<Place> update = Wrappers.lambdaUpdate(Place.class).eq(Place::getId, id);
        boolean changed = false;

        if (request.name() != null) {
            update.set(Place::getName, requireName(request.name()));
            changed = true;
        }
        if (request.visitedOn() != null) {
            update.set(Place::getVisitedOn, request.visitedOn());
            changed = true;
        }
        if (request.rating() != null) {
            update.set(Place::getRating, request.rating());
            changed = true;
        }
        if (request.note() != null) {
            update.set(Place::getNote, blankToNull(request.note()));
            changed = true;
        }
        if (request.adcode() != null) {
            update.set(Place::getAdcode, blankToNull(request.adcode()));
            changed = true;
        }
        if (request.province() != null) {
            update.set(Place::getProvince, blankToNull(request.province()));
            changed = true;
        }
        if (request.city() != null) {
            update.set(Place::getCity, blankToNull(request.city()));
            changed = true;
        }
        if (hasAnyCoordinate(request)) {
            Coords coords = resolveCoordinates(request.lng(), request.lat(), request.lngWgs(), request.latWgs());
            update.set(Place::getLng, coords.lng())
                    .set(Place::getLat, coords.lat())
                    .set(Place::getLngWgs, coords.lngWgs())
                    .set(Place::getLatWgs, coords.latWgs());
            changed = true;
        }

        if (!changed) {
            return PlaceVO.from(existing);
        }

        // 表里没有 updated_at 触发器，只能显式写
        update.set(Place::getUpdatedAt, OffsetDateTime.now());
        placeMapper.update(null, update);
        return PlaceVO.from(placeMapper.selectById(id));
    }

    /**
     * 软删：只打删除标记并留一条事件，照片由 media 侧对账清理（P6-T03）。
     *
     * <p>两步必须同一事务：只置标记不写事件，照片永远没人清理；写了事件标记没落，
     * media 会去处理一条还活着的记录。</p>
     *
     * <p>幂等：重复删、删不存在的 id 都直接返回成功，不报 10404、不重复写事件——
     * DELETE 对同一目标调任意次结果应当一致。</p>
     */
    @Transactional
    public void delete(long id) {
        Place existing = placeMapper.selectById(id);
        if (existing == null || existing.getDeletedAt() != null) {
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        // 条件里再判一次 isNull(deletedAt)：先查后改之间若被并发删掉，这次 update 影响 0 行，
        // 据此跳过事件，避免同一地点留下两条 PLACE_DELETED
        int affected = placeMapper.update(null, Wrappers.lambdaUpdate(Place.class)
                .eq(Place::getId, id)
                .isNull(Place::getDeletedAt)
                .set(Place::getDeletedAt, now)
                .set(Place::getUpdatedAt, now));
        if (affected == 0) {
            return;
        }

        PlaceEvent event = new PlaceEvent();
        event.setPlaceId(id);
        event.setEventType(PlaceEventType.PLACE_DELETED.name());
        // 0 = 待处理，与 place_event.status 的列注释一致；payload 未映射，交给列默认值 '{}'
        event.setStatus(0);
        placeEventMapper.insert(event);
    }

    /** 取一条未软删的记录；不存在或已删都是 10404（对调用方而言没有区别）。 */
    private Place requireActive(long id) {
        Place place = placeMapper.selectById(id);
        if (place == null || place.getDeletedAt() != null) {
            throw new BizException(ErrorCode.NOT_FOUND, "地点不存在");
        }
        return place;
    }

    /**
     * 判定以哪套坐标为真值，并算出另一套（口径写死，别改）：
     *
     * <pre>
     * 传了 lngWgs/latWgs  → 以它为 WGS-84 真值，正算得到 GCJ
     * 没传                → 把 lng/lat 当 GCJ 真值，反解得到 WGS
     * 两者都传但互相矛盾   → 以 WGS 为准（EXIF 是原始数据，GCJ 是派生数据，原始值丢了拿不回来）
     * </pre>
     *
     * <p>只给一半报 10400 而不是"凑合用另一个"：那说明调用方装配请求时出了 bug，
     * 静默接受会写进一个经纬度来自两个城市的点，看着"有值"却无从发现。</p>
     */
    private static Coords resolveCoordinates(BigDecimal lng, BigDecimal lat,
                                             BigDecimal lngWgs, BigDecimal latWgs) {
        if (lngWgs != null || latWgs != null) {
            if (lngWgs == null || latWgs == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "lngWgs 与 latWgs 必须成对出现");
            }
            double[] gcj = CoordinateConverter.wgs84ToGcj02(lngWgs.doubleValue(), latWgs.doubleValue());
            return new Coords(toCoordinate(gcj[0]), toCoordinate(gcj[1]),
                    toCoordinate(lngWgs.doubleValue()), toCoordinate(latWgs.doubleValue()));
        }

        if (lng == null || lat == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "lng 与 lat 必须成对出现");
        }
        double[] wgs = CoordinateConverter.gcj02ToWgs84(lng.doubleValue(), lat.doubleValue());
        return new Coords(toCoordinate(lng.doubleValue()), toCoordinate(lat.doubleValue()),
                toCoordinate(wgs[0]), toCoordinate(wgs[1]));
    }

    private static boolean hasAnyCoordinate(PlaceUpdateRequest request) {
        return request.lng() != null || request.lat() != null
                || request.lngWgs() != null || request.latWgs() != null;
    }

    /** 6 位小数对齐 {@code NUMERIC(10,6)}；用截断会让正算/反解的往返误差单向累积。 */
    private static BigDecimal toCoordinate(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    /** 字段级校验已拦住全空格，这里再兜一次：T12 的内部接口会绕过 controller 直接调服务。 */
    private static String requireName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "name 不能为空");
        }
        return name;
    }

    /**
     * 空串归一成 null。
     *
     * <p>{@code adcode} 在库里有 {@code CHECK (adcode IS NULL OR adcode ~ '^[0-9]{6}$')}，
     * 表单没选行政区时前端传 {@code ""}——直接写进去不是"空"，是撞约束报 50000。</p>
     */
    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 四套坐标的落库值。换算逻辑只在这里做一次，新增与修改共用。 */
    private record Coords(BigDecimal lng, BigDecimal lat, BigDecimal lngWgs, BigDecimal latWgs) {
    }
}
