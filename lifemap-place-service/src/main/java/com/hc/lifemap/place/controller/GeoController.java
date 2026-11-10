package com.hc.lifemap.place.controller;

import com.hc.lifemap.common.api.ApiResponse;
import com.hc.lifemap.place.client.AmapPoi;
import com.hc.lifemap.place.service.GeoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 地理入口：POI 搜索（T10）与逆地理（T11）。
 *
 * <p>{@code keywords} 故意声明成必填：漏传能落到统一的"缺少必填参数 → 10400"，
 * 而空串、纯空格由 {@link GeoService} 判掉，两处都不发高德请求。</p>
 */
@RestController
@RequestMapping("/api/geo")
public class GeoController {

    private final GeoService geoService;

    public GeoController(GeoService geoService) {
        this.geoService = geoService;
    }

    /**
     * 关键词搜 POI，供表单的搜索框做候选列表。
     *
     * @param keywords 关键词，不能为空
     * @param city     城市名或 adcode，可选；同名地点很多（"鼓楼"），带上城市才准
     * @param page     页码，从 1 开始，默认 1
     */
    @GetMapping("/search")
    public ApiResponse<List<AmapPoi>> search(@RequestParam String keywords,
                                             @RequestParam(required = false) String city,
                                             @RequestParam(defaultValue = "1") Integer page) {
        return ApiResponse.ok(geoService.search(keywords, city, page));
    }
}
