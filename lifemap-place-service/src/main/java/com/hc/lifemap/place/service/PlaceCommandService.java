package com.hc.lifemap.place.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hc.lifemap.common.api.ErrorCode;
import com.hc.lifemap.place.dto.PlaceCreateRequest;
import com.hc.lifemap.place.dto.PlaceUpdateRequest;
import com.hc.lifemap.place.dto.PlaceVO;
import com.hc.lifemap.place.entity.Place;
import com.hc.lifemap.place.exception.BizException;
import com.hc.lifemap.place.mapper.PlaceMapper;
import com.hc.lifemap.place.util.CoordinateConverter;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

/**
 * 地点写入侧：新增（T05）、局部更新与软删（T08/T09）。
 *
 * <p>与 {@link PlaceQueryService} 分开，不是为了对称好看，而是两侧的失败语义不一样：
 * 读侧的错误几乎都是"参数不合法"，写侧还有"目标不存在"和"事务回滚"，混在一个类里
 * 迟早会出现"查询方法顺手写了一条"。T09 的软删还会给本类加 {@code @Transactional}。</p>
 */
@Service
public class PlaceCommandService {

    private final PlaceMapper placeMapper;

    public PlaceCommandService(PlaceMapper placeMapper) {
        this.placeMapper = placeMapper;
    }

    /**
     * 新增一条到访记录，返回带 {@code id} 与时间戳的完整对象。
     *
     * <p>落库后再 {@code selectById} 回查一次是刻意的：{@code created_at/updated_at} 由 PG 的
     * {@code DEFAULT now()} 生成，实体上标了 {@code insertStrategy = NEVER}，插入后内存里的
     * 这两个字段仍是 null。API 契约要求返回 {@code createdAt}，前端也靠它做"刚记的"排序，
     * 所以宁可多一次主键查询，也不自己在 Java 侧造时间——两套时钟一定会对不上。</p>
     *
     * <p>不加 {@code @Transactional}：单条 insert 自身就是一个事务，多包一层只是让
     * "插入成功但回查失败"这种情况也能被一起回滚，而那种失败本来就该暴露出来。</p>
     */
    public PlaceVO create(PlaceCreateRequest request) {
        // 字段级 @NotBlank 已经拦住全空格，这里再兜一次：T12 的内部接口会绕过 controller 直接调服务
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "name 不能为空");
        }

        Coords coords = resolveCoordinates(request.lng(), request.lat(),
                request.lngWgs(), request.latWgs());

        Place place = new Place();
        place.setName(name);
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
     * 局部更新：只改请求里出现过的字段，其余保持原值。
     *
     * <p>用 {@link LambdaUpdateWrapper} 逐个 {@code set}，而不是 {@code updateById(实体)}：
     * 后者会把实体上的 null 一并写进库（请求没传的字段就被清空了），要绕开它只能塞一个半满的
     * 实体进去，那又会顺带覆盖期间别人改过的列。字段一个都没传时直接返回当前值，
     * 不产生一次只有 {@code updated_at} 变化的空写。</p>
     *
     * <p>{@code updated_at} 必须显式写：表里没有触发器（T03 建表时定的）。</p>
     */
    public PlaceVO update(long id, PlaceUpdateRequest request) {
        Place existing = requireActive(id);

        LambdaUpdateWrapper<Place> update = Wrappers.lambdaUpdate(Place.class).eq(Place::getId, id);
        boolean changed = false;

        if (request.name() != null) {
            // 名称不允许被改成空白，走与新增相同的 trim + 非空判断
            String name = request.name().trim();
            if (name.isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "name 不能为空");
            }
            update.set(Place::getName, name);
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
            // 空串 = 主动清空这条笔记，与"没传"（null）区分开
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
            // 坐标是四列一组：给了任意一个就走统一口径把四列一起重算，
            // 避免库里出现"GCJ 是新的、WGS 还是旧的"这种半套坐标
            Coords coords = resolveCoordinates(request.lng(), request.lat(),
                    request.lngWgs(), request.latWgs());
            update.set(Place::getLng, coords.lng())
                    .set(Place::getLat, coords.lat())
                    .set(Place::getLngWgs, coords.lngWgs())
                    .set(Place::getLatWgs, coords.latWgs());
            changed = true;
        }

        if (!changed) {
            return PlaceVO.from(existing);
        }

        update.set(Place::getUpdatedAt, OffsetDateTime.now());
        placeMapper.update(null, update);
        return PlaceVO.from(placeMapper.selectById(id));
    }

    /**
     * 取一条未软删的记录，取不到就是 10404。
     *
     * <p>软删的记录对外等同不存在：详情接口给 10404，更新也不该写进去——否则会出现
     * "列表里看不见、却还能改得动"的状态。</p>
     */
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
     * 传了 lngWgs/latWgs  → 以它为 WGS-84 真值，正算得到 GCJ 存 lng/lat
     * 没传                → 把 lng/lat 当 GCJ 真值，反解得到 WGS 存 lng_wgs/lat_wgs
     * 两者都传但互相矛盾   → 以 WGS 为准（EXIF 是原始数据，GCJ 是派生数据）
     * </pre>
     *
     * <p>为什么"WGS 优先"：地图点击取点其实也是拿 WGS 算出来再转 GCJ 的，EXIF 则是设备直接写的
     * 原始值。两套打架时信原始值，派生值无论怎么错都能重算，原始值丢了就再也拿不回来。</p>
     *
     * <p>只给一半（只 {@code lngWgs} 或只 {@code latWgs}）报 10400 而不是"凑合用另一半"：
     * 那说明调用方装配请求时出了 bug，静默接受会写进一个经纬度来自两个城市的点，
     * 在地图上看着"有值"，实际无从发现。</p>
     *
     * <p>结果统一按 6 位小数、{@code HALF_UP} 落 {@link BigDecimal}——与 {@code NUMERIC(10,6)}
     * 对齐。不截断，是因为截断会让正算/反解的往返误差单向累积。</p>
     */
    private static Coords resolveCoordinates(BigDecimal lng, BigDecimal lat,
                                             BigDecimal lngWgs, BigDecimal latWgs) {
        if (lngWgs != null || latWgs != null) {
            if (lngWgs == null || latWgs == null) {
                throw new BizException(ErrorCode.BAD_REQUEST, "lngWgs 与 latWgs 必须成对出现");
            }
            double[] gcj = CoordinateConverter.wgs84ToGcj02(lngWgs.doubleValue(), latWgs.doubleValue());
            return new Coords(
                    toCoordinate(gcj[0]),
                    toCoordinate(gcj[1]),
                    toCoordinate(lngWgs.doubleValue()),
                    toCoordinate(latWgs.doubleValue()));
        }

        // 新增时 lng/lat 由 @NotNull 兜住；更新时只给一个，说明调用方漏了另一半
        if (lng == null || lat == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "lng 与 lat 必须成对出现");
        }
        double[] wgs = CoordinateConverter.gcj02ToWgs84(lng.doubleValue(), lat.doubleValue());
        return new Coords(
                toCoordinate(lng.doubleValue()),
                toCoordinate(lat.doubleValue()),
                toCoordinate(wgs[0]),
                toCoordinate(wgs[1]));
    }

    private static boolean hasAnyCoordinate(PlaceUpdateRequest request) {
        return request.lng() != null || request.lat() != null
                || request.lngWgs() != null || request.latWgs() != null;
    }

    /** double → NUMERIC(10,6) 的定点数。 */
    private static BigDecimal toCoordinate(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * 空串归一成 null。
     *
     * <p>{@code adcode} 在库里是 {@code CHAR(6)} 且带 {@code CHECK (adcode IS NULL OR adcode ~ '^[0-9]{6}$')}，
     * 表单没选行政区时前端往往传 {@code ""}——直接写进去不是"空"，是撞约束报 50000。
     * 其余文本字段一并归一，避免列表里出现 {@code note: ""} 这种"有值但没内容"的状态，
     * 让前端少一个 {@code note === '' || note === null} 的判断。</p>
     */
    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 四套坐标的落库值。换算只做一次，新增与修改共用。 */
    private record Coords(BigDecimal lng, BigDecimal lat, BigDecimal lngWgs, BigDecimal latWgs) {
    }
}
