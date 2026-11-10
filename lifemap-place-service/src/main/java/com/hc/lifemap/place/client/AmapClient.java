package com.hc.lifemap.place.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.hc.lifemap.common.api.ErrorCode;
import com.hc.lifemap.place.config.AmapProperties;
import com.hc.lifemap.place.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 高德 Web 服务代理（POI 搜索；逆地理由 P1-T11 加）。
 *
 * <p>只做三件事：拼请求、判 {@code status}、把报文映射成本项目结构。所有失败都翻译成 20500
 * 加一句人话，既不把高德原始报文丢给前端，也不让 Key 出现在日志里——高德异常自带的 message
 * 里有完整 URI（含 {@code key=}），所以异常只记状态码，绝不记 {@code ex.getMessage()}。</p>
 */
@Component
public class AmapClient {

    private static final Logger log = LoggerFactory.getLogger(AmapClient.class);

    private static final String PLACE_TEXT_PATH = "/v3/place/text";

    /** 单页固定 20 条：前端下拉列表一屏就够，多取只会让响应变慢。 */
    private static final int PAGE_SIZE = 20;

    private final AmapProperties properties;
    private final RestClient restClient;

    public AmapClient(AmapProperties properties, RestClient.Builder builder) {
        // 必须显式给 requestFactory：RestClient 默认不设超时，断网时请求会一直挂着
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.timeout());
        factory.setReadTimeout(properties.timeout());

        this.properties = properties;
        this.restClient = builder.baseUrl(properties.baseUrl()).requestFactory(factory).build();
    }

    /**
     * 关键词搜 POI。
     *
     * @param keywords 已 trim 过且非空，调用方（{@code GeoService}）负责校验
     * @param city     城市名或 adcode，null 表示不限
     * @param page     从 1 开始
     */
    public List<AmapPoi> searchPoi(String keywords, String city, int page) {
        if (!StringUtils.hasText(properties.key())) {
            throw new BizException(ErrorCode.GEO_PROVIDER_ERROR, "未配置高德 Web 服务 Key");
        }

        JsonNode body;
        try {
            body = restClient.get()
                    .uri(placeTextUri(keywords, city, page))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException ex) {
            log.warn("高德 POI 搜索 HTTP 状态异常 status={} keywords={}", ex.getStatusCode().value(), keywords);
            throw new BizException(ErrorCode.GEO_PROVIDER_ERROR, "高德接口调用失败");
        } catch (ResourceAccessException ex) {
            // 只记异常类型：message 里带着含 key 的完整 URL
            log.warn("高德 POI 搜索连接失败 type={} keywords={}", ex.getClass().getSimpleName(), keywords);
            throw new BizException(ErrorCode.GEO_PROVIDER_ERROR, "高德接口超时或无法连接");
        }

        requireSuccess(body);
        return parsePois(body);
    }

    /**
     * 拼出完整的请求 URI。
     *
     * <p>必须是绝对地址：{@code RestClient.uri(URI)} 传绝对 URI 时不会再拼 baseUrl，
     * 拿相对路径过去会直接 {@code IllegalArgumentException: URI is not absolute}。</p>
     *
     * <p>关键词里可能有中文、空格，这里显式 {@code encode()} 一次；交给框架做会看调用方式，
     * 传 {@code URI} 的那条路径是原样使用，不编码就发不出去。</p>
     */
    URI placeTextUri(String keywords, String city, int page) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(properties.baseUrl() + PLACE_TEXT_PATH)
                .queryParam("key", properties.key())
                .queryParam("keywords", keywords)
                .queryParam("offset", PAGE_SIZE)
                .queryParam("page", page)
                // 只要基础字段，带 extensions=all 会多出图片、评分等一大坨用不上的数据
                .queryParam("extensions", "base");
        if (city != null) {
            builder.queryParam("city", city);
        }
        return builder.build().encode().toUri();
    }

    /**
     * 高德的成败用字符串 {@code status} 表示，{@code "1"} 才是成功；失败原因在 {@code infocode}。
     *
     * <p>只把常见几种换成能指导操作的中文，其余统一"调用失败"——原始报文对用户没有意义，
     * 详细 infocode 记在日志里供排查。</p>
     */
    static void requireSuccess(JsonNode body) {
        String status = body == null ? null : AmapPoi.text(body, "status");
        if ("1".equals(status)) {
            return;
        }
        String infocode = body == null ? null : AmapPoi.text(body, "infocode");
        log.warn("高德 POI 搜索返回失败 status={} infocode={}", status, infocode);
        throw new BizException(ErrorCode.GEO_PROVIDER_ERROR, messageFor(infocode));
    }

    private static String messageFor(String infocode) {
        return switch (infocode == null ? "" : infocode) {
            // 10001 无效 Key；10009 Key 与平台类型不匹配（用了 JS Key 调 Web 服务）
            case "10001", "10009" -> "高德 Key 无效或类型不对（需要 Web 服务 Key）";
            case "10003", "10044" -> "高德接口今日调用量已超限";
            case "10004" -> "高德接口调用过于频繁，请稍后再试";
            default -> "高德接口调用失败";
        };
    }

    /** {@code pois} 缺失或为空数组时返回空列表：搜不到不是错误，前端展示"没有结果"即可。 */
    static List<AmapPoi> parsePois(JsonNode body) {
        List<AmapPoi> pois = new ArrayList<>();
        if (body == null) {
            return pois;
        }
        for (JsonNode poi : body.path("pois")) {
            pois.add(AmapPoi.from(poi));
        }
        return pois;
    }
}
