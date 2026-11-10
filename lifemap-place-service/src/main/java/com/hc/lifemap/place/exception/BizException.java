package com.hc.lifemap.place.exception;

import com.hc.lifemap.common.api.ErrorCode;

/**
 * 业务异常：可预期的失败，不打印堆栈，不产生 500。
 *
 * <p>code 的唯一真源是 {@link ErrorCode}，本类不允许携带字符串错误码；
 * 抛出方只需关心"哪一类错误"，由 {@link GlobalExceptionHandler} 统一翻译成 HTTP 200 + 业务码。</p>
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    /** 错误码自带文案不够具体时（例如参数校验失败）用自定义文案覆盖。 */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /** 暴露枚举而非裸 int，让调用方还能读到语义与默认文案。 */
    public ErrorCode errorCode() {
        return errorCode;
    }
}
