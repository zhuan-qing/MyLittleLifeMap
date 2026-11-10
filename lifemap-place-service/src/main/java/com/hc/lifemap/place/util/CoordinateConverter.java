package com.hc.lifemap.place.util;

/**
 * WGS-84 ⇄ GCJ-02 双向换算（纯函数，无状态、不联网、不碰库）。
 *
 * <p>照片 EXIF 与地图原始点位是 WGS-84，高德底图吃 GCJ-02，国内同一地点两者差 300–800 米，
 * 不换算就会"点的是西湖，标记落在隔壁小区"。</p>
 *
 * <p>算法是公开的克拉索夫斯基椭球近似：{@code transformLat/transformLng} 给出以米为单位的
 * 偏移量，再按子午圈、卯酉圈曲率半径换算成度。那一步量纲换算最容易出错——漏掉 {@code π}
 * 结果照样算得出来、不抛异常，只是偏出几十公里。</p>
 *
 * <p>反解没有解析解，用不动点迭代：以 GCJ 坐标为初值，每轮正算一次、按差值回退，
 * 收敛到 1e-9 度（约 0.1 毫米）或用满 15 轮为止。</p>
 */
public final class CoordinateConverter {

    /** 长半轴与偏心率平方：GCJ-02 定义所用的克拉索夫斯基椭球，不是 WGS-84 的 6378137。 */
    private static final double A = 6378245.0;
    private static final double EE = 0.00669342162296594323;

    /** 迭代上限。正常 3–5 轮收敛，给 15 是为了"绝不可能是死循环"这件事有代码保证。 */
    private static final int MAX_ITERATIONS = 15;

    /** 收敛阈值（度）。1e-9 度远小于 {@code NUMERIC(10,6)} 的存储精度。 */
    private static final double TOLERANCE = 1e-9;

    private static final double PI = Math.PI;

    /**
     * 国内粗略矩形。故意不用省界多边形：GCJ-02 只在国境内生效，边界外几百米的误差对
     * "照片有没有出国"毫无影响，而查省界要额外带一份地理数据。
     */
    private static final double CHINA_MIN_LNG = 72.004;
    private static final double CHINA_MAX_LNG = 137.8347;
    private static final double CHINA_MIN_LAT = 0.8293;
    private static final double CHINA_MAX_LAT = 55.8271;

    private CoordinateConverter() {
    }

    /**
     * WGS-84 → GCJ-02 正算。
     *
     * @return {@code double[2]}：{@code [0]=经度, [1]=纬度}，境外原样返回
     */
    public static double[] wgs84ToGcj02(double lng, double lat) {
        if (outOfChina(lng, lat)) {
            return new double[]{lng, lat};
        }
        double[] delta = delta(lng, lat);
        return new double[]{lng + delta[0], lat + delta[1]};
    }

    /**
     * GCJ-02 → WGS-84 反解（不动点迭代）。
     *
     * @return {@code double[2]}：{@code [0]=经度, [1]=纬度}，境外原样返回
     */
    public static double[] gcj02ToWgs84(double lng, double lat) {
        if (outOfChina(lng, lat)) {
            return new double[]{lng, lat};
        }
        // 初值必须用 GCJ 坐标本身：偏移只有 0.004 度量级，从 0 起步前几轮会跑到海里去
        double wgsLng = lng;
        double wgsLat = lat;
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            double[] forward = wgs84ToGcj02(wgsLng, wgsLat);
            double diffLng = forward[0] - lng;
            double diffLat = forward[1] - lat;
            wgsLng -= diffLng;
            wgsLat -= diffLat;
            if (Math.abs(diffLng) < TOLERANCE && Math.abs(diffLat) < TOLERANCE) {
                break;
            }
        }
        return new double[]{wgsLng, wgsLat};
    }

    /**
     * 是否在境内矩形之外。单独抽出来是为了能被测试直接打到：境外判断写错时，
     * 正算/反解会把境外坐标也硬加上几百米偏移，而往返测试仍然是绿的。
     */
    static boolean outOfChina(double lng, double lat) {
        return lng < CHINA_MIN_LNG || lng > CHINA_MAX_LNG
                || lat < CHINA_MIN_LAT || lat > CHINA_MAX_LAT;
    }

    /** 经纬两个方向的偏移量（度）。{@code magic} 即 1 − ee·sin²(lat)，卯酉圈修正项。 */
    private static double[] delta(double lng, double lat) {
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);

        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);

        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
        return new double[]{dLng, dLat};
    }

    /** 纬度方向偏移量（米）。以 (105°E, 35°N) 为原点的经验拟合式，公开算法原文照搬。 */
    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320.0 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    /** 经度方向偏移量（米）。 */
    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }
}
