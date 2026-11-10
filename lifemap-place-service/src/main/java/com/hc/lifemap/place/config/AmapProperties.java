package com.hc.lifemap.place.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 高德 Web 服务配置（{@code lifemap.geo.amap}）。
 *
 * <p>{@code key} 只从 Nacos 或环境变量注入，代码库里不出现真实 Key；为空时客户端直接报 20500
 * 且不发请求。要用「Web 服务」类型的 Key——错用「Web 端(JS)」会返回 INVALID_USER_SCODE，
 * 比"没配 Key"难查得多。</p>
 *
 * <p>{@code timeout} 兜底 3 秒：RestClient 默认不设超时，断网时会一直挂着。</p>
 */
@ConfigurationProperties(prefix = "lifemap.geo.amap")
public record AmapProperties(String key, String baseUrl, Duration timeout) {

    public AmapProperties {
        baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "https://restapi.amap.com" : baseUrl.trim();
        // 去掉结尾斜杠：客户端用 baseUrl + path 直接拼，留着会拼出 //v3/place/text
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        timeout = timeout == null ? Duration.ofSeconds(3) : timeout;
    }
}
