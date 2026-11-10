package com.hc.lifemap.place.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
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

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 高德 Web 服务代理：POI 搜索与逆地理。
 *
 * <p>只做三件事：拼请求、判 {@code status}、把报文映射成本项目结构。所有失败都翻译成 20500
 * 加一句人话，既不把高德原始报文丢给前端，也不让 Key 出现在日志里——高德异常自带的 message
 * 里有完整 URI（含 {@code key=}），所以异常只记状态码，绝不记 {@code ex.getMessage()}。</p>
 */
@Component
public class AmapClient {

    private static final Logger log = LoggerFactory.getLogger(AmapClient.class);

    private static final String PLACE_TEXT_PATH = "/v3/place/text";
    private static final String REGEO_PATH = "/v3/geocode/regeo";

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
        requireKey();
        JsonNode body = fetch(placeTextUri(keywords, city, page), "POI 搜索 keywords=" + keywords);
        requireSuccess(body);
        return parsePois(body);
    }

    /**
     * 坐标反查行政区，给"地图上随手点一个点"用。
     *
     * <p>境外坐标高德多半仍返回 {@code status=1}，只是 {@code addressComponent} 是空的，
     * 所以这里不报错、返回四个 null 字段——"没有国内行政区"是正常结果，不是故障。</p>
     *
     * @param lng 经度；{@code location} 的顺序是 {@code lng,lat}，写反会落到地球另一端
     */
    public Regeo regeo(BigDecimal lng, BigDecimal lat) {
        requireKey();
        JsonNode body = fetch(regeoUri(lng, lat), "逆地理");
        requireSuccess(body);
        return parseRegeo(body);
    }

    /** 逆地理结果：行政区四件套，前端拿来直接填表。 */
    public record Regeo(String province, String city, String adcode, String address) {
    }

    private void requireKey() {
        if (!StringUtils.hasText(properties.key())) {
            throw new BizException(ErrorCode.GEO_PROVIDER_ERROR, "未配置高德 Web 服务 Key");
        }
    }

    /**
     * 发一次 GET 并把失败统一翻译成 20500。
     *
     * <p>{@code action} 只用于日志，不能拼进 URL——日志里一旦出现带 {@code key=} 的地址，
     * Key 就跟着日志进了工单和截图。</p>
     */
    private JsonNode fetch(URI uri, String action) {
        try {
            return restClient.get().uri(uri).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException ex) {
            log.warn("高德{} HTTP 状态异常 status={}", action, ex.getStatusCode().value());
            throw new BizException(ErrorCode.GEO_PROVIDER_ERROR, "高德接口调用失败");
        } catch (ResourceAccessException ex) {
            // 只记异常类型：message 里带着含 key 的完整 URL
            log.warn("高德{}连接失败 type={}", action, ex.getClass().getSimpleName());
            throw new BizException(ErrorCode.GEO_PROVIDER_ERROR, "高德接口超时或无法连接");
        }
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

    URI regeoUri(BigDecimal lng, BigDecimal lat) {
        return UriComponentsBuilder.fromUriString(properties.baseUrl() + REGEO_PATH)
                .queryParam("key", properties.key())
                // 顺序是 lng,lat：写反了高德不报错，只会落到地球另一端
                .queryParam("location", lng.toPlainString() + "," + lat.toPlainString())
                .queryParam("extensions", "base")
                .build().encode().toUri();
    }

    /**
     * 从 {@code regeocode.addressComponent} 里取行政区。
     *
     * <p>直辖市的 {@code city} 是空数组，回落成省名（北京 → 北京市），否则前端填出来的
     * 城市列会是空的。</p>
     */
    static Regeo parseRegeo(JsonNode body) {
        JsonNode regeocode = body == null ? MissingNode.getInstance() : body.path("regeocode");
        JsonNode component = regeocode.path("addressComponent");

        String province = AmapPoi.text(component, "province");
        String city = AmapPoi.text(component, "city");

        return new Regeo(
                province,
                city != null ? city : province,
                AmapPoi.text(component, "adcode"),
                AmapPoi.text(regeocode, "formatted_address"));
    }
}
