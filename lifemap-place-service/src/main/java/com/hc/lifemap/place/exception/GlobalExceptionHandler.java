package com.hc.lifemap.place.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.hc.lifemap.common.api.ApiResponse;
import com.hc.lifemap.common.api.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * 全局异常翻译：可预期的失败一律 HTTP 200 + 业务码（dev_doc §6.2），
 * 只有未捕获异常才落到 50000。
 *
 * <p><b>basePackages 必须限定在 controller</b>：不限包时 {@code /actuator/health} 也会被本
 * advice 接管，返回结构变成 {@code ApiResponse}，健康检查脚本会判失败。</p>
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

    /** 字段校验失败：拼成「字段 提示; 字段 提示」，一次性告诉前端所有问题。 */
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
     * 类型转换失败（{@code ?year=abc}）与缺必填参数不归 {@code @Valid} 管，
     * 漏接就会被 {@code Exception} 兜底成 50000，把"参数写错了"误报成"服务挂了"。
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ApiResponse<Void> handleBadParameter(Exception ex) {
        String message = (ex instanceof MethodArgumentTypeMismatchException mismatch)
                ? "参数 " + mismatch.getName() + " 类型不合法"
                : "缺少必填参数 " + ((MissingServletRequestParameterException) ex).getParameterName();
        log.warn("参数不合法: {}", message);
        return ApiResponse.fail(ErrorCode.BAD_REQUEST, message);
    }

    /**
     * 请求体能读成 JSON、但某字段的值转不过去（{@code "visitedOn":"2026/10/01"}）。
     * 它在 Jackson 反序列化阶段就失败，{@code @Valid} 压根没机会执行。
     *
     * <p>沿 cause 链找 {@link InvalidFormatException} 而不是取 {@code getMostSpecificCause()}：
     * 日期解析失败的根因是 {@code DateTimeParseException}，不带字段路径，字段名会丢——
     * 而那正是这条提示唯一有用的信息。</p>
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<Void> handleUnreadableBody(HttpMessageNotReadableException ex) {
        InvalidFormatException invalid = null;
        for (Throwable cursor = ex; cursor != null && invalid == null; cursor = cursor.getCause()) {
            if (cursor instanceof InvalidFormatException candidate) {
                invalid = candidate;
            }
        }

        String message = (invalid != null && !invalid.getPath().isEmpty())
                ? invalid.getPath().stream()
                        .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                        .collect(Collectors.joining(".")) + " 格式不合法"
                : "请求体格式不合法";
        log.warn("请求体解析失败: {}", message);
        return ApiResponse.fail(ErrorCode.BAD_REQUEST, message);
    }

    /** 兜底：真出了 bug 才走这里，必须留堆栈。 */
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnexpected(Exception ex) {
        log.error("未捕获异常", ex);
        return ApiResponse.fail(ErrorCode.INTERNAL_ERROR);
    }
}
