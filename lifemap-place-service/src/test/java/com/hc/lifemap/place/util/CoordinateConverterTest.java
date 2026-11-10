package com.hc.lifemap.place.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 坐标换算的三类断言：往返一致、偏移量合理、境外不偏移。
 *
 * <p>这三条互相补位，少一条就会漏一类 bug：只测往返，正算和反解同时写错符号也能互相对上；
 * 只测偏移量，量纲漏了 {@code π} 会偏出几十公里却仍然"有偏移"；只测境外，
 * 国内那条路径错了照样绿。</p>
 */
class CoordinateConverterTest {

    /** 往返允许误差（度）。1e-6 度约 0.1 米，比 NUMERIC(10,6) 的末位大一档，足够严。 */
    private static final double ROUND_TRIP_TOLERANCE = 1e-6;

    @Test
    @DisplayName("wgs → gcj → wgs 往返后回到原点")
    void roundTripKeepsOriginalCoordinate() {
        // 杭州 / 北京 / 乌鲁木齐：分别落在东部、华北、西北，偏移量各不相同
        double[][] points = {
                {120.144521, 30.245103},
                {116.397499, 39.908722},
                {87.616848, 43.825592}
        };

        for (double[] point : points) {
            double[] gcj = CoordinateConverter.wgs84ToGcj02(point[0], point[1]);
            double[] back = CoordinateConverter.gcj02ToWgs84(gcj[0], gcj[1]);

            assertEquals(point[0], back[0], ROUND_TRIP_TOLERANCE,
                    "经度往返误差超限，原点 lng=" + point[0]);
            assertEquals(point[1], back[1], ROUND_TRIP_TOLERANCE,
                    "纬度往返误差超限，原点 lat=" + point[1]);
        }
    }

    @Test
    @DisplayName("国内点正算后位移落在 100–1000 米之间")
    void offsetIsWithinRealisticRange() {
        // 北京天安门附近：正算后应位移数百米，既不是 0（没生效）也不是几公里（量纲错）
        double[] wgs = {116.397499, 39.908722};
        double[] gcj = CoordinateConverter.wgs84ToGcj02(wgs[0], wgs[1]);

        double distance = distanceInMeters(wgs[0], wgs[1], gcj[0], gcj[1]);
        assertTrue(distance > 100 && distance < 1000,
                "正算位移应落在 100–1000 米，实际 " + distance + " 米");
    }

    @Test
    @DisplayName("境外坐标原样返回，不加任何偏移")
    void outOfChinaCoordinateIsReturnedAsIs() {
        // 伦敦：outOfChina 生效，正算与反解都必须是恒等变换
        double[] london = {-0.127758, 51.507351};
        double[] gcj = CoordinateConverter.wgs84ToGcj02(london[0], london[1]);
        double[] back = CoordinateConverter.gcj02ToWgs84(london[0], london[1]);

        assertEquals(london[0], gcj[0], 0d, "境外经度被改动了");
        assertEquals(london[1], gcj[1], 0d, "境外纬度被改动了");
        assertEquals(london[0], back[0], 0d, "境外反解经度被改动了");
        assertEquals(london[1], back[1], 0d, "境外反解纬度被改动了");
    }

    @Test
    @DisplayName("漠河（国内最北）往返仍收敛，不会因 cos(lat) 趋小而发散")
    void northernmostPointStillConverges() {
        double[] mohe = {122.538589, 52.970117};

        assertFalse(CoordinateConverter.outOfChina(mohe[0], mohe[1]), "漠河应判定为国内");
        double[] gcj = CoordinateConverter.wgs84ToGcj02(mohe[0], mohe[1]);
        double[] back = CoordinateConverter.gcj02ToWgs84(gcj[0], gcj[1]);

        assertEquals(mohe[0], back[0], ROUND_TRIP_TOLERANCE);
        assertEquals(mohe[1], back[1], ROUND_TRIP_TOLERANCE);
    }

    /** 球面距离（米），只用于断言量级，不追求大地测量精度。 */
    private static double distanceInMeters(double lng1, double lat1, double lng2, double lat2) {
        double radLat1 = Math.toRadians(lat1);
        double radLat2 = Math.toRadians(lat2);
        double deltaLat = radLat2 - radLat1;
        double deltaLng = Math.toRadians(lng2 - lng1);

        double h = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(radLat1) * Math.cos(radLat2) * Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2);
        return 2 * 6371000 * Math.asin(Math.sqrt(h));
    }
}
