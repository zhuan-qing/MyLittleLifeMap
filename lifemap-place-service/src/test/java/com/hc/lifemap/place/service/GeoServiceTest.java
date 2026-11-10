package com.hc.lifemap.place.service;

import com.hc.lifemap.common.api.ErrorCode;
import com.hc.lifemap.place.client.AmapClient;
import com.hc.lifemap.place.client.AmapPoi;
import com.hc.lifemap.place.exception.BizException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
}
