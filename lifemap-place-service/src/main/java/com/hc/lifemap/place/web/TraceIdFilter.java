package com.hc.lifemap.place.web;

import com.hc.lifemap.common.constant.HeaderConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 链路 ID 入口：入站取 {@code X-Trace-Id}，没有就生成 16 位，写进 MDC 与响应头，
 * 日志 pattern 用 {@code %X{traceId}} 直接引用。
 *
 * <p>{@code finally} 里必须清 MDC：Tomcat 线程是复用的，不清会把上一个请求的 traceId
 * 带进下一个请求，排查时看到的是错号。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 与网关生成的位数一致。 */
    private static final int TRACE_ID_LENGTH = 16;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(HeaderConstants.TRACE_ID));
        MDC.put(HeaderConstants.MDC_TRACE_ID, traceId);
        response.setHeader(HeaderConstants.TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(HeaderConstants.MDC_TRACE_ID);
        }
    }

    /** 直连本服务调试时没有网关注入，这里兜一个，保证任何入口都带 traceId。 */
    private String resolveTraceId(String inbound) {
        if (StringUtils.hasText(inbound)) {
            return inbound.trim();
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, TRACE_ID_LENGTH);
    }
}
