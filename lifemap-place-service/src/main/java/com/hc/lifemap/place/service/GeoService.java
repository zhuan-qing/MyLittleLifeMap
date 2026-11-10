package com.hc.lifemap.place.service;

import com.hc.lifemap.common.api.ErrorCode;
import com.hc.lifemap.place.client.AmapClient;
import com.hc.lifemap.place.client.AmapPoi;
import com.hc.lifemap.place.exception.BizException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 地理编排：把前端的搜索条件规整成高德能吃的参数，再把结果原样递回去。
 *
 * <p>校验放在这里而不是 controller：T12 的内部接口、P4 的批量补 adcode 都会直接调本服务，
 * 规则只写在前门就会留缺口。</p>
 */
@Service
public class GeoService {

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
}
