package com.hc.lifemap.common.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @Test
    void okResponseCarriesDataAndZeroCode() {
        ApiResponse<String> response = ApiResponse.ok("place-1").withTraceId("trace-abc");

        assertEquals(0, response.code());
        assertEquals("place-1", response.data());
        assertEquals("trace-abc", response.traceId());
    }

    @Test
    void failResponseCarriesErrorCodeAndNoData() {
        ApiResponse<Void> response = ApiResponse.fail(ErrorCode.NOT_FOUND);

        assertEquals(ErrorCode.NOT_FOUND.code(), response.code());
        assertEquals(ErrorCode.NOT_FOUND.message(), response.message());
        assertNull(response.data());
    }
}
