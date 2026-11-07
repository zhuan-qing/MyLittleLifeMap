package com.hc.lifemap.common.constant;

/**
 * 链路透传头常量。
 *
 * <p>JWT 只在网关校验一次，向下游透传用户标识与 traceId；服务之间内网直连，不再重复鉴权。</p>
 */
public final class HeaderConstants {

    /** 网关注入的当前用户 ID。 */
    public static final String USER_ID = "X-User-Id";

    /** 网关生成的链路 ID，全链路透传，日志按此聚合。 */
    public static final String TRACE_ID = "X-Trace-Id";

    /** MDC key，与上面的头一一对应，便于 logback pattern 直接引用。 */
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_USER_ID = "userId";

    /** 请求进入网关的时间戳（毫秒），用于统计端到端耗时。 */
    public static final String REQUEST_AT = "X-Request-At";

    private HeaderConstants() {
    }
}
