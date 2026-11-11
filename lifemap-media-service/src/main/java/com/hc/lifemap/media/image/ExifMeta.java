package com.hc.lifemap.media.image;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * 一张照片的 EXIF 关键信息：方向、拍摄时间、EXIF 里记的宽高。
 *
 * <p>{@code orientation} 取 1–8：1 是正常，2/4 是镜像，3 是 180°，5–8 带 90° 旋转。
 * 渲染派生图时只有两件事要做——要不要转置（5–8 长宽互换）、要不要翻转，见 {@link #transposed()}。</p>
 *
 * <p>{@code takenAt} 可能为 null（EXIF 里没有拍摄时间），由调用方回落成上传时间。
 * EXIF 本身不带时区，这里按本机时区解释。</p>
 */
public record ExifMeta(int orientation, OffsetDateTime takenAt, Integer width, Integer height) {

    /** 正常方向。 */
    public static final int NORMAL = 1;

    /** 5–8 这四种方向把长宽换了位，派生图必须先转置再缩放。 */
    private static final Set<Integer> TRANSPOSED = Set.of(5, 6, 7, 8);

    public ExifMeta {
        // 设备写坏、或者手改了 EXIF 的情况不罕见，越界值一律当正常，别让上传挂在一个字段上
        if (orientation < 1 || orientation > 8) {
            orientation = NORMAL;
        }
    }

    /** 没有 EXIF、或 EXIF 读不出来时用它：方向正常、没有拍摄时间、没有尺寸。 */
    public static ExifMeta none() {
        return new ExifMeta(NORMAL, null, null, null);
    }

    public boolean transposed() {
        return TRANSPOSED.contains(orientation);
    }
}
