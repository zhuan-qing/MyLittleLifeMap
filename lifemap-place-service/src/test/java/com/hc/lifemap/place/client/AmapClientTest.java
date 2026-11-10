package com.hc.lifemap.place.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hc.lifemap.common.api.ErrorCode;
import com.hc.lifemap.place.config.AmapProperties;
import com.hc.lifemap.place.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报文映射与错误翻译的单元测试：不打网络，只喂 JSON。
 *
 * <p>重点在三类高德"脏数据"上——{@code cityname} 是空数组、{@code location} 拆不开、
 * {@code pois} 不存在。它们在真实报文里都出现过，一旦按 String 直接反序列化就会整批炸掉。</p>
 */
class AmapClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    @Test
    @DisplayName("正常 POI 解析出六个回填字段，location 拆成 lng/lat")
    void parsesNormalPoi() throws Exception {
        JsonNode poi = json("""
                {"id":"B0FFH8","name":"西湖风景名胜区","location":"120.148871,30.247482",
                 "pname":"浙江省","cityname":"杭州市","adcode":"330106","address":"龙井路1号"}
                """);

        AmapPoi result = AmapPoi.from(poi);

        assertEquals("B0FFH8", result.id());
        assertEquals("西湖风景名胜区", result.name());
        assertEquals(0, new BigDecimal("120.148871").compareTo(result.lng()));
        assertEquals(0, new BigDecimal("30.247482").compareTo(result.lat()));
        assertEquals("浙江省", result.province());
        assertEquals("杭州市", result.city());
        assertEquals("330106", result.adcode());
        assertEquals("龙井路1号", result.address());
    }

    @Test
    @DisplayName("直辖市的 cityname / 空 address 是空数组，归一成 null 而不是 \"[]\"")
    void treatsEmptyArrayAsNull() throws Exception {
        // 北京市区的真实报文形态：cityname 与 address 都可能是 []
        JsonNode poi = json("""
                {"id":"B000A7","name":"天安门","location":"116.397428,39.90923",
                 "pname":"北京市","cityname":[],"adcode":"110101","address":[]}
                """);

        AmapPoi result = AmapPoi.from(poi);

        assertNull(result.city(), "空数组必须变成 null，否则前端会显示 []");
        assertNull(result.address());
        assertEquals("北京市", result.province());
    }

    @Test
    @DisplayName("location 缺失或格式不对时坐标给 null，不抛异常")
    void toleratesBrokenLocation() throws Exception {
        assertNull(AmapPoi.from(json("{\"name\":\"某地\",\"location\":[]}")).lng());
        assertNull(AmapPoi.from(json("{\"name\":\"某地\"}")).lat());
        assertNull(AmapPoi.from(json("{\"name\":\"某地\",\"location\":\"120.1\"}")).lng());
    }

    @Test
    @DisplayName("status=1 视为成功；pois 缺失返回空列表")
    void acceptsSuccessWithEmptyResult() throws Exception {
        AmapClient.requireSuccess(json("{\"status\":\"1\",\"infocode\":\"10000\",\"count\":\"0\"}"));

        List<AmapPoi> pois = AmapClient.parsePois(json("{\"status\":\"1\",\"count\":\"0\"}"));
        assertTrue(pois.isEmpty(), "搜不到不是错误，应返回空列表");
    }

    @Test
    @DisplayName("status=0 按 infocode 翻成可操作的中文，不泄漏原始报文")
    void mapsFailureByInfocode() throws Exception {
        assertEquals("高德 Key 无效或类型不对（需要 Web 服务 Key）",
                failureMessage("{\"status\":\"0\",\"info\":\"INVALID_USER_KEY\",\"infocode\":\"10001\"}"));
        assertEquals("高德接口调用过于频繁，请稍后再试",
                failureMessage("{\"status\":\"0\",\"info\":\"ACCESS_TOO_FREQUENT\",\"infocode\":\"10004\"}"));
        assertEquals("高德接口调用失败",
                failureMessage("{\"status\":\"0\",\"info\":\"ENGINE_RESPONSE_DATA_ERROR\",\"infocode\":\"20003\"}"));
    }

    @Test
    @DisplayName("未配置 Key 时直接报 20500，不发请求")
    void rejectsMissingKeyBeforeRequest() {
        // baseUrl/timeout 传 null 走兜底值；builder 用裸的，构造过程不会发请求
        AmapClient client = new AmapClient(new AmapProperties("", null, null), RestClient.builder());

        BizException ex = assertThrows(BizException.class, () -> client.searchPoi("西湖", null, 1));
        assertEquals(ErrorCode.GEO_PROVIDER_ERROR, ex.errorCode());
        assertTrue(ex.getMessage().contains("Key"), "文案要指向 Key 未配置这个真实原因");
    }

    /**
     * 相对 URI 交给 {@code RestClient.uri(URI)} 会抛 {@code URI is not absolute}，
     * 关键词里的中文也必须先编码——这两点都只能靠看拼出来的 URI 断言。
     */
    @Test
    @DisplayName("请求 URI 是绝对地址且关键词已编码")
    void buildsAbsoluteEncodedUri() {
        AmapClient client = new AmapClient(
                new AmapProperties("k123", "https://restapi.amap.com/", null), RestClient.builder());

        URI uri = client.placeTextUri("西湖 断桥", "杭州", 2);
        // 用 getRawQuery()：getQuery() 会把中文解码回来，看不出到底有没有编码
        String query = uri.getRawQuery();

        assertTrue(uri.isAbsolute(), "必须是绝对 URI：" + uri);
        assertEquals("https", uri.getScheme());
        assertEquals("/v3/place/text", uri.getPath(), "baseUrl 末尾的斜杠不能拼成 //v3");
        assertTrue(query.contains("keywords=%E8%A5%BF%E6%B9%96"), "中文应已编码：" + uri);
        assertTrue(query.contains("page=2"));
        assertTrue(query.contains("city=%E6%9D%AD%E5%B7%9E"));
    }

    @Test
    @DisplayName("city 为 null 时不带该参数")
    void omitsCityWhenAbsent() {
        AmapClient client = new AmapClient(
                new AmapProperties("k123", null, null), RestClient.builder());

        String query = client.placeTextUri("西湖", null, 1).getRawQuery();

        assertFalse(query.contains("city="), "没传城市就不该出现空 city 参数：" + query);
    }

    private static String failureMessage(String body) throws Exception {
        BizException ex = assertThrows(BizException.class, () -> AmapClient.requireSuccess(json(body)));
        assertEquals(ErrorCode.GEO_PROVIDER_ERROR, ex.errorCode());
        return ex.getMessage();
    }
}
