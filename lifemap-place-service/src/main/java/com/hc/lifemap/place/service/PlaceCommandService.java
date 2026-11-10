package com.hc.lifemap.place.service;

import com.hc.lifemap.common.api.ErrorCode;
import com.hc.lifemap.place.dto.PlaceCreateRequest;
import com.hc.lifemap.place.dto.PlaceVO;
import com.hc.lifemap.place.entity.Place;
import com.hc.lifemap.place.exception.BizException;
import com.hc.lifemap.place.mapper.PlaceMapper;
import com.hc.lifemap.place.util.CoordinateConverter;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

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

        Place place = new Place();
        place.setName(name);
        applyCoordinates(place, request);
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
     * 判定以哪套坐标为真值，并补算出另一套写进实体（口径写死，别改）：
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
    private void applyCoordinates(Place place, PlaceCreateRequest request) {
        boolean hasWgsLng = request.lngWgs() != null;
        boolean hasWgsLat = request.latWgs() != null;
        if (hasWgsLng != hasWgsLat) {
            throw new BizException(ErrorCode.BAD_REQUEST, "lngWgs 与 latWgs 必须成对出现");
        }

        if (hasWgsLng) {
            double wgsLng = request.lngWgs().doubleValue();
            double wgsLat = request.latWgs().doubleValue();
            double[] gcj = CoordinateConverter.wgs84ToGcj02(wgsLng, wgsLat);
            place.setLng(toCoordinate(gcj[0]));
            place.setLat(toCoordinate(gcj[1]));
            place.setLngWgs(toCoordinate(wgsLng));
            place.setLatWgs(toCoordinate(wgsLat));
            return;
        }

        double gcjLng = request.lng().doubleValue();
        double gcjLat = request.lat().doubleValue();
        double[] wgs = CoordinateConverter.gcj02ToWgs84(gcjLng, gcjLat);
        place.setLng(toCoordinate(gcjLng));
        place.setLat(toCoordinate(gcjLat));
        place.setLngWgs(toCoordinate(wgs[0]));
        place.setLatWgs(toCoordinate(wgs[1]));
    }

    /** double → NUMERIC(10,6) 的定点数。 */
    private BigDecimal toCoordinate(double value) {
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
    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
