package com.hc.lifemap.place.web;

import com.hc.lifemap.common.api.ApiResponse;
import com.hc.lifemap.common.constant.HeaderConstants;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 把 {@link TraceIdFilter} 写进 MDC 的 traceId 回填到 {@code ApiResponse}，
 * 业务代码不必为一个链路号到处传参。
 *
 * <p>只认 {@code ApiResponse}（{@link #supports}），actuator 等非本项目结构的响应不碰。</p>
 *
 * <p><b>basePackages 取整个服务包，不能只写 controller</b>：异常响应由
 * {@code com.hc.lifemap.place.exception.GlobalExceptionHandler} 产生，
 * {@code ControllerAdviceBean} 判适用性时看的是产出响应的那个 Bean 的包，只圈 controller
 * 会让错误响应漏掉 traceId（表现为成功有、失败是 null）。</p>
 */
@RestControllerAdvice(basePackages = "com.hc.lifemap.place")
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return ApiResponse.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        // ApiResponse 是 record，withTraceId 返回新实例：必须用返回值
        if (body instanceof ApiResponse<?> apiResponse) {
            return apiResponse.withTraceId(MDC.get(HeaderConstants.MDC_TRACE_ID));
        }
        return body;
    }
}
