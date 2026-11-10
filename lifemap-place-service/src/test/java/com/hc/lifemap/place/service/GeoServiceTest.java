package com.hc.lifemap.place.service;

import com.hc.lifemap.common.api.ErrorCode;
import com.hc.lifemap.place.client.AmapClient;
import com.hc.lifemap.place.client.AmapPoi;
import com.hc.lifemap.place.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入参规整与转发：空白关键词、非法页码都不能穿过到高德那边去花配额。
 */
@ExtendWith(MockitoExtension.class)
class GeoServiceTest {

    @Mock
    private AmapClient amapClient;

    @InjectMocks
    private GeoService geoService;

    @Test
    void blankKeywordsIsRejectedWithoutCallingProvider() {
        for (String keywords : new String[]{null, "", "   "}) {
            BizException ex = assertThrows(BizException.class,
                    () -> geoService.search(keywords, "杭州", 1));
            assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
        }
        verify(amapClient, never()).searchPoi(any(), any(), anyInt());
    }

    @Test
    void pageBelowOneIsRejected() {
        BizException ex = assertThrows(BizException.class,
                () -> geoService.search("西湖", null, 0));
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
    }

    @Test
    void trimsKeywordsAndBlankCityBecomesNull() {
        List<AmapPoi> expected = List.of();
        when(amapClient.searchPoi("西湖", null, 1)).thenReturn(expected);

        // 城市传空串时不该把 "  " 原样发给高德：那会变成一次"城市不存在"的空结果
        assertEquals(expected, geoService.search("  西湖  ", "  ", null));
        verify(amapClient).searchPoi("西湖", null, 1);
    }

    @Test
    @DisplayName("越界坐标在发请求前就被拦掉")
    void regeoRejectsOutOfRangeCoordinates() {
        assertBadRequest("200", "30");
        assertBadRequest("-181", "30");
        assertBadRequest("120", "91");
        assertBadRequest("120", null);

        verify(amapClient, never()).regeo(any(), any());
    }

    @Test
    void regeoDelegatesValidCoordinates() {
        AmapClient.Regeo expected = new AmapClient.Regeo("浙江省", "杭州市", "330106", "西湖区北山街道");
        when(amapClient.regeo(new BigDecimal("120.148871"), new BigDecimal("30.247482"))).thenReturn(expected);

        assertEquals(expected, geoService.regeo(new BigDecimal("120.148871"), new BigDecimal("30.247482")));
    }

    private void assertBadRequest(String lng, String lat) {
        BigDecimal lngValue = lng == null ? null : new BigDecimal(lng);
        BigDecimal latValue = lat == null ? null : new BigDecimal(lat);

        BizException ex = assertThrows(BizException.class, () -> geoService.regeo(lngValue, latValue));
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode());
    }
}
