package com.hc.lifemap.place.service;

import com.hc.lifemap.common.api.ErrorCode;
import com.hc.lifemap.place.dto.PlaceCreateRequest;
import com.hc.lifemap.place.dto.PlaceVO;
import com.hc.lifemap.place.entity.Place;
import com.hc.lifemap.place.exception.BizException;
import com.hc.lifemap.place.mapper.PlaceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 写到库里的四个坐标字段到底填了什么的单元测试。
 *
 * <p>用 Mockito 而不是起 Spring 上下文：本卡要验证的是"判定口径"这一条纯逻辑，
 * 连数据库跑一遍会把 400 毫秒的启动时间和一个外部依赖挂到每次构建上，
 * 而真正需要真库验证的列映射早已在 {@code PlaceMapperTest} 里覆盖。</p>
 *
 * <p>{@code insert} 的桩会顺手把实体存进 {@link #inserted}、并回填 id：真实的
 * {@code created_at} 由 PG 生成，所以 service 落库后会 {@code selectById} 回查一次，
 * 桩必须让这条回查拿到刚插进去的那个对象，否则服务里的 {@code PlaceVO.from(null)} 会空指针。</p>
 */
@ExtendWith(MockitoExtension.class)
class PlaceServiceTest {

    private static final long GENERATED_ID = 7L;

    /** 杭州西湖，GCJ-02。 */
    private static final double GCJ_LNG = 120.148871;
    private static final double GCJ_LAT = 30.247482;

    @Mock
    private PlaceMapper placeMapper;

    private final AtomicReference<Place> inserted = new AtomicReference<>();

    private PlaceCommandService placeCommandService;

    @BeforeEach
    void setUp() {
        placeCommandService = new PlaceCommandService(placeMapper);
    }

    /** 让 insert"成功并回填主键"，让 selectById 回查到刚插入的那一行。只在真正会落库的用例里调。 */
    private void stubSuccessfulInsert() {
        when(placeMapper.insert(any(Place.class))).thenAnswer(invocation -> {
            Place place = invocation.getArgument(0);
            place.setId(GENERATED_ID);
            inserted.set(place);
            return 1;
        });
        when(placeMapper.selectById(GENERATED_ID)).thenAnswer(invocation -> inserted.get());
    }

    @Test
    @DisplayName("只给 GCJ 坐标时，自动反解出 WGS 并一起落库")
    void gcjOnlyInputFillsWgsColumns() {
        stubSuccessfulInsert();

        PlaceVO vo = placeCommandService.create(request(new BigDecimal("120.148871"),
                new BigDecimal("30.247482"), null, null));

        Place saved = inserted.get();
        assertNotNull(saved, "insert 没有被调用");
        // GCJ 原样保留
        assertEquals(0, new BigDecimal("120.148871").compareTo(saved.getLng()), "GCJ 经度不该被改动");
        assertEquals(0, new BigDecimal("30.247482").compareTo(saved.getLat()), "GCJ 纬度不该被改动");
        // WGS 必须补出来，且在国内应比 GCJ 小约 0.004 度（杭州约 400 米）
        assertNotNull(saved.getLngWgs(), "lng_wgs 不能为 null");
        assertNotNull(saved.getLatWgs(), "lat_wgs 不能为 null");
        double lngShift = saved.getLng().doubleValue() - saved.getLngWgs().doubleValue();
        assertTrue(lngShift > 0.002 && lngShift < 0.006, "经度偏移量应在 0.002–0.006 度，实际 " + lngShift);
        // 杭州一带纬度方向是反的：WGS 纬度比 GCJ 纬度大约 0.0023 度。这条断言专防"两套抄成一套"
        assertTrue(saved.getLatWgs().compareTo(saved.getLat()) > 0, "此处 WGS 纬度应大于 GCJ 纬度");

        // 出参也带上两套坐标，前端详情页能直接看
        assertNotNull(vo.lngWgs());
        assertEquals(saved.getLngWgs(), vo.lngWgs());
    }

    @Test
    @DisplayName("给了 WGS 坐标时以它为真值正算 GCJ，忽略请求里的 lng/lat")
    void wgsInputOverridesGcj() {
        stubSuccessfulInsert();

        PlaceVO vo = placeCommandService.create(request(BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("120.144521"), new BigDecimal("30.245103")));

        Place saved = inserted.get();
        // WGS 原值被完整保留
        assertEquals(0, new BigDecimal("120.144521").compareTo(saved.getLngWgs()), "WGS 经度不该被改动");
        assertEquals(0, new BigDecimal("30.245103").compareTo(saved.getLatWgs()), "WGS 纬度不该被改动");
        // GCJ 由正算得到，不是请求里那两个 0。
        // 注意杭州处纬度偏移是负的：WGS(30.245103) 正算后 GCJ 纬度约 30.242790，比原值更小。
        // 偏移方向随地区变号（北京、乌鲁木齐为正，上海、杭州为负），别按"总是往北偏"写断言。
        assertEquals(120.1492, saved.getLng().doubleValue(), 5e-4, "GCJ 经度应由正算得到");
        assertEquals(30.2428, saved.getLat().doubleValue(), 5e-4, "GCJ 纬度应由正算得到");

        assertNotNull(vo.lng());
    }

    @Test
    @DisplayName("WGS 只给一半时报 10400，不猜另一半")
    void halfPairWgsIsRejected() {
        BizException ex = assertThrows(BizException.class,
                () -> placeCommandService.create(request(new BigDecimal("120.148871"),
                        new BigDecimal("30.247482"), new BigDecimal("120.144521"), null)));

        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
    }

    /** 构造一个只关心坐标的请求，其余字段给最小合法值。 */
    private PlaceCreateRequest request(BigDecimal lng, BigDecimal lat,
                                       BigDecimal lngWgs, BigDecimal latWgs) {
        return new PlaceCreateRequest("西湖", lng, lat, lngWgs, latWgs,
                null, null, null, LocalDate.of(2026, 10, 3), 5, null);
    }
}
