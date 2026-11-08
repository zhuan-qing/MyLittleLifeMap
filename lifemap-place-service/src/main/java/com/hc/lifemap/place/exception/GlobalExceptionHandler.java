package com.hc.lifemap.place.exception;

import com.hc.lifemap.common.api.ApiResponse;
import com.hc.lifemap.common.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * 全局异常翻译：三类异常 → 统一响应体，HTTP 状态码一律 200。
 *
 * <p>口径见 dev_doc §6.2：前端只判 {@code code}，不必同时处理两套语义；
 * 只有框架级未捕获异常才落到 50000。</p>
 *
 * <p><b>必须限定 basePackages</b>：不限包时 {@code /actuator/health} 也会被本 advice 接管，
 * 返回结构变成 {@code ApiResponse}，健康检查脚本会判失败。</p>
 */
@RestControllerAdvice(basePackages = "com.hc.lifemap.place.controller")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 可预期的业务失败：原样透出错误码与文案，不打堆栈（它不是 bug）。 */
    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBiz(BizException ex) {
        log.warn("业务异常 code={} message={}", ex.errorCode().code(), ex.getMessage());
        return ApiResponse.fail(ex.errorCode(), ex.getMessage());
    }

    /** 请求体字段校验失败：拼成「字段 提示; 字段 提示」，一次性告诉前端所有问题。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleInvalidArgument(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        String message = detail.isEmpty() ? ErrorCode.BAD_REQUEST.message() : detail;
        log.warn("参数校验失败: {}", message);
        return ApiResponse.fail(ErrorCode.BAD_REQUEST, message);
    }

    /**
     * 参数层还有两类异常不归 {@code @Valid} 管：类型转换失败（{@code ?year=abc}）、缺必填参数。
     * <p>它们同属调用方错误，必须归到 10400；漏掉就会被下面的 {@code Exception} 兜底成 50000，
     * 把"参数写错了"误报成"服务挂了"，后面每张卡的验证期望值都会跟着错。</p>
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ApiResponse<Void> handleBadParameter(Exception ex) {
        String message = (ex instanceof MethodArgumentTypeMismatchException mismatch)
                ? "参数 " + mismatch.getName() + " 类型不合法"
                : "缺少必填参数 " + ((MissingServletRequestParameterException) ex).getParameterName();
        log.warn("参数不合法: {}", message);
        return ApiResponse.fail(ErrorCode.BAD_REQUEST, message);
    }

    /** 兜底：真出了 bug 才走这里，必须留堆栈。 */
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnexpected(Exception ex) {
        log.error("未捕获异常", ex);
        return ApiResponse.fail(ErrorCode.INTERNAL_ERROR);
    }
}
