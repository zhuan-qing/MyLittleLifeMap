package com.hc.lifemap.common.api;

import java.time.Instant;

/**
 * 统一响应体。所有业务接口一律返回该结构，HTTP 状态码只表达传输层结果。
 *
 * @param code      业务码，0 表示成功，取值见 {@link ErrorCode}
 * @param message   提示文案，可直接展示给用户
 * @param data      业务数据
 * @param traceId   链路 ID，由网关生成，便于前后端对账
 * @param timestamp 服务端时间戳
 */
public record ApiResponse<T>(
        int code,
        String message,
        T data,
        String traceId,
        Instant timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.OK.code(), ErrorCode.OK.message(), data, null, Instant.now());
    }

    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.message());
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.code(), message, null, null, Instant.now());
    }

    public ApiResponse<T> withTraceId(String traceId) {
        return new ApiResponse<>(code, message, data, traceId, timestamp);
    }
}
