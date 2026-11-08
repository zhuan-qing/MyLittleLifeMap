package com.hc.lifemap.place.controller;

import com.hc.lifemap.common.api.ApiResponse;
import com.hc.lifemap.common.api.ErrorCode;
import com.hc.lifemap.place.exception.BizException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 探针：不碰数据库，只用来验证「统一响应体 + traceId + 错误码」这条骨架通了。
 *
 * <p>后续卡可以继续用它确认服务活着，P3 网关接入后也靠它验证 traceId 是否透传。</p>
 */
@RestController
@RequestMapping("/api")
public class PingController {

    /**
     * @param fail 传任意值即模拟一次业务错误，用于验证错误口径
     */
    @GetMapping("/ping")
    public ApiResponse<String> ping(@RequestParam(required = false) Integer fail) {
        if (fail != null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "演示用参数错误");
        }
        return ApiResponse.ok("pong");
    }
}
