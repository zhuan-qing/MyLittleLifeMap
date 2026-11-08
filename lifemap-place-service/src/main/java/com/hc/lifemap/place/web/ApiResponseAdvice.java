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
 * 响应体回填：把 {@link TraceIdFilter} 写进 MDC 的 traceId 补进 {@code ApiResponse}。
 *
 * <p>好处是业务代码不用为了一个链路号到处传参——controller 直接 {@code ApiResponse.ok(data)}，
 * 出门时统一带上。</p>
 *
 * <p>只认 {@code ApiResponse}（{@link #supports}），actuator、错误页等非本项目结构的响应不碰。</p>
 *
 * <p><b>basePackages 取整个服务包，不能只写 controller</b>：异常响应由
 * {@code com.hc.lifemap.place.exception.GlobalExceptionHandler} 产生，
 * {@code ControllerAdviceBean} 判适用性时看的是处理响应那个 Bean 的包，
 * 只圈 controller 会让错误响应漏掉 traceId（表现为成功有、失败是 null）。
 * 放到 {@code com.hc.lifemap.place} 后 actuator（在 Spring Boot 自己的包里）依然不受影响。</p>
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
        // ApiResponse 是 record，withTraceId 返回新实例：必须用返回值，改原对象无效。
        if (body instanceof ApiResponse<?> apiResponse) {
            return apiResponse.withTraceId(MDC.get(HeaderConstants.MDC_TRACE_ID));
        }
        return body;
    }
}
