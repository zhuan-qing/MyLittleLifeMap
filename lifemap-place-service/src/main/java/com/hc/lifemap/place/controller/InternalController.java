package com.hc.lifemap.place.controller;

import com.hc.lifemap.common.api.ApiResponse;
import com.hc.lifemap.common.api.ErrorCode;
import com.hc.lifemap.place.exception.BizException;
import com.hc.lifemap.place.mapper.PlaceMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 只对内网的接口：把去重后的有效 adcode 递给 stats。
 *
 * <p>为什么要有它：adcode 在 place 库里，而 stats 服务按规矩不能直连别人的库；聚合本身
 * （省市点亮、统计）在 P4 做，这里只负责把原料稳定地交出去。</p>
 *
 * <p><b>网关绝不能路由 {@code /internal/**}</b>（P3 会复核一次）：这个接口没有鉴权，
 * 暴露到公网等于把全量行政区数据送出去。</p>
 */
@RestController
@RequestMapping("/internal/places")
public class InternalController {

    /** 一次最多给 5000 条：全国区县总共三千出头，再多说明调用方把参数写错了。 */
    private static final int MAX_SIZE = 5000;

    private final PlaceMapper placeMapper;

    public InternalController(PlaceMapper placeMapper) {
        this.placeMapper = placeMapper;
    }

    /**
     * 去重 adcode 列表。
     *
     * @param page 页码，从 1 开始
     * @param size 每页条数，默认 2000，超出 5000 压到 5000
     */
    @GetMapping("/codes")
    public ApiResponse<CodePage> codes(@RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "2000") int size) {
        if (page < 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "page 必须从 1 开始");
        }

        int pageSize = Math.clamp(size, 1, MAX_SIZE);
        List<String> codes = placeMapper.selectDistinctAdcodes(pageSize, (page - 1) * pageSize);
        return ApiResponse.ok(new CodePage(codes, placeMapper.countDistinctAdcodes()));
    }

    /**
     * @param codes 本页 adcode，已去重、无 null
     * @param total 去重后的总数，调用方据此判断是否还要继续翻
     */
    public record CodePage(List<String> codes, long total) {
    }
}
