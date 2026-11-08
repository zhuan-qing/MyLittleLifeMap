package com.hc.lifemap.place.controller;

import com.hc.lifemap.common.api.ApiResponse;
import com.hc.lifemap.place.dto.PageResult;
import com.hc.lifemap.place.dto.PlaceListItemVO;
import com.hc.lifemap.place.service.PlaceQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 地点 REST 入口。本卡只开列表，POST/PUT/DELETE 由 T05、T09 陆续加上。
 *
 * <p>controller 只做两件事：把查询串绑到方法参数、把结果套进统一响应体。
 * 参数范围校验（{@code page<1} → 10400、{@code size} 夹到 100）留在
 * {@link PlaceQueryService}——T12 的内部接口会绕过这里直接调服务，规则不能只写一份在前门。</p>
 *
 * <p>参数一律用 {@code defaultValue} 给默认值而不是 {@code required=true}：
 * 前端首屏就是不带参数的裸请求，缺省不该变成一次错误提示。</p>
 */
@RestController
@RequestMapping("/api/places")
public class PlaceController {

    private final PlaceQueryService placeQueryService;

    public PlaceController(PlaceQueryService placeQueryService) {
        this.placeQueryService = placeQueryService;
    }

    /**
     * 时间轴列表：分页 + 年份筛选。
     *
     * @param page 页码，从 1 开始，默认 1
     * @param size 每页条数，默认 20，上限 100
     * @param year 只看这一年；省略表示全部年份
     */
    @GetMapping
    public ApiResponse<PageResult<PlaceListItemVO>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) Integer year) {
        return ApiResponse.ok(placeQueryService.list(page, size, year));
    }
}
