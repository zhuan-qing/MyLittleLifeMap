package com.hc.lifemap.place.service;

import com.hc.lifemap.common.api.ErrorCode;
import com.hc.lifemap.place.client.AmapClient;
import com.hc.lifemap.place.client.AmapPoi;
import com.hc.lifemap.place.exception.BizException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;

/**
 * 地理编排：把前端的搜索条件规整成高德能吃的参数，再把结果原样递回去。
 *
 * <p>校验放在这里而不是 controller：T12 的内部接口、P4 的批量补 adcode 都会直接调本服务，
 * 规则只写在前门就会留缺口。</p>
 */
@Service
public class GeoService {

    private static final BigDecimal LNG_MIN = new BigDecimal("-180");
    private static final BigDecimal LNG_MAX = new BigDecimal("180");
    private static final BigDecimal LAT_MIN = new BigDecimal("-90");
    private static final BigDecimal LAT_MAX = new BigDecimal("90");

    private final AmapClient amapClient;

    public GeoService(AmapClient amapClient) {
        this.amapClient = amapClient;
    }

    /**
     * 关键词搜 POI。
     *
     * @param keywords 空白视为调用方写错，报 10400 且不发请求（省一次配额）
     * @param city     城市名或 adcode，可选
     * @param page     从 1 开始，null 视为第 1 页
     */
    public List<AmapPoi> search(String keywords, String city, Integer page) {
        if (keywords == null || keywords.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "keywords 不能为空");
        }
        int pageNo = page == null ? 1 : page;
        if (pageNo < 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "page 必须从 1 开始");
        }

        String normalizedCity = StringUtils.hasText(city) ? city.trim() : null;
        return amapClient.searchPoi(keywords.trim(), normalizedCity, pageNo);
    }

    /**
     * 坐标反查行政区。
     *
     * <p>越界坐标在发请求前就拦掉：高德对 {@code lng=200} 只会返回空结果，而调用方真正的问题是
     * 参数写错了，报 10400 比给一个空壳有用。</p>
     *
     * @param lng 经度，−180–180
     * @param lat 纬度，−90–90
     */
    public AmapClient.Regeo regeo(BigDecimal lng, BigDecimal lat) {
        if (lng == null || lat == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "lng 与 lat 都不能为空");
        }
        if (lng.compareTo(LNG_MAX) > 0 || lng.compareTo(LNG_MIN) < 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "经度必须在 -180 到 180 之间");
        }
        if (lat.compareTo(LAT_MAX) > 0 || lat.compareTo(LAT_MIN) < 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "纬度必须在 -90 到 90 之间");
        }
        return amapClient.regeo(lng, lat);
    }
}
