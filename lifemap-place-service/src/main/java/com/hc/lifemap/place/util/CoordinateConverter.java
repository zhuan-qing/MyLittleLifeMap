package com.hc.lifemap.place.util;

/**
 * WGS-84 ⇄ GCJ-02 双向换算（纯函数，无状态、不联网、不碰库）。
 *
 * <p>为什么必须有这一层：手机照片 EXIF、地图原始点位给的都是 WGS-84（GPS 真实经纬度），
 * 而高德底图吃 GCJ-02（"火星坐标"，国内强制偏移）。国内同一地点两者差 300–800 米——
 * 不换算，用户点的是西湖，标记会落到隔壁小区。</p>
 *
 * <p>算法用的是公开的克拉索夫斯基椭球近似：先把经纬度差投到平面上算两个方向的偏移量
 * （{@code transformLat/transformLng}），再按子午圈、卯酉圈曲率半径换算回"度"。
 * 最后那一步的量纲换算是整段代码最容易出错的地方——漏掉 {@code π}，结果照样能算出来、
 * 不抛任何异常，只是偏了几十公里。</p>
 *
 * <p>反解没有解析解（正算里的三角函数无法求逆），用不动点迭代：以 GCJ 坐标为初值，
 * 每轮正算一次、按差值回退，收敛到 1e-9 度（约 0.1 毫米）或用满 15 轮就停。</p>
 */
public final class CoordinateConverter {

    /** 长半轴，克拉索夫斯基椭球（GCJ-02 定义所用的椭球，不是 WGS-84 的 6378137）。 */
    private static final double A = 6378245.0;

    /** 偏心率平方，同上。 */
    private static final double EE = 0.00669342162296594323;

    /** 迭代次数上限。正常 3–5 轮就收敛；给 15 是为了"绝不可能是死循环"这件事有代码保证。 */
    private static final int MAX_ITERATIONS = 15;

    /** 收敛阈值，单位：度。1e-9 度约 0.1 毫米，远小于 NUMERIC(10,6) 的存储精度。 */
    private static final double TOLERANCE = 1e-9;

    private static final double PI = Math.PI;

    /**
     * 国内粗略矩形范围。故意用矩形而不是省界多边形：GCJ-02 本身只在国境内生效，
     * 边界外几百米的误差对"照片有没有出国"这个判断没有影响，而查省界要带一份地理数据进来。
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
     * @param lng WGS-84 经度
     * @param lat WGS-84 纬度
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
     * @param lng GCJ-02 经度
     * @param lat GCJ-02 纬度
     * @return {@code double[2]}：{@code [0]=经度, [1]=纬度}，境外原样返回
     */
    public static double[] gcj02ToWgs84(double lng, double lat) {
        if (outOfChina(lng, lat)) {
            return new double[]{lng, lat};
        }
        // 初值必须用 GCJ 坐标本身：偏移量只有 0.004 度量级，从 0 起步前几轮会跑到海里去
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
     * 是否在国内粗略矩形之外。
     *
     * <p>单独抽成方法是为了能被测试单独打到：正算/反解在境外都是"原样返回"，
     * 一旦这个判断写错，境外坐标会被硬加上几百米偏移，而单看往返测试还是绿的。</p>
     */
    static boolean outOfChina(double lng, double lat) {
        return lng < CHINA_MIN_LNG || lng > CHINA_MAX_LNG
                || lat < CHINA_MIN_LAT || lat > CHINA_MAX_LAT;
    }

    /**
     * 算出某点经纬两个方向的偏移量，单位：度。
     *
     * <p>{@code dLat/dLng} 的公式给出的是"米"，最后两行把它除以所在纬度的曲率半径，
     * 换算成度。{@code magic} 是 1 - ee·sin²(lat)，即卯酉圈修正项。</p>
     */
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

    /** 纬度方向的偏移量（米）。以 (105°E, 35°N) 为原点的经验拟合式，公开算法原文照搬。 */
    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320.0 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    /** 经度方向的偏移量（米）。 */
    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }
}
