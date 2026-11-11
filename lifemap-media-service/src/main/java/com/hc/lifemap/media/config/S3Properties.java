package com.hc.lifemap.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 对象存储配置（{@code lifemap.storage.s3}），指向本机 RustFS。
 *
 * <p>前缀叫 s3 而不是 rustfs：RustFS 是 S3 兼容实现，将来换实现只改 endpoint，
 * 代码与配置结构都不用动。{@code bucket} 同理只从配置注入，不写死。</p>
 *
 * <p>{@code presignTtl} 兜底 1 小时——S3 的签名最长 7 天，不显式给就得自己记住这个上限。</p>
 */
@ConfigurationProperties(prefix = "lifemap.storage.s3")
public record S3Properties(String endpoint, String accessKey, String secretKey, String bucket,
                           Duration presignTtl, String originPrefix, String webpPrefix, String thumbPrefix) {

    /** S3 硬上限：预签名有效期超过 7 天，SDK 直接拒绝。 */
    private static final Duration MAX_PRESIGN_TTL = Duration.ofDays(7);

    public S3Properties {
        endpoint = trimTrailingSlash(endpoint);
        // 配成 0 或负数时 signatureDuration 会抛异常，这里提前兜住
        if (presignTtl == null || presignTtl.isZero() || presignTtl.isNegative()) {
            presignTtl = Duration.ofHours(1);
        } else if (presignTtl.compareTo(MAX_PRESIGN_TTL) > 0) {
            presignTtl = MAX_PRESIGN_TTL;
        }
        // 同桶不同前缀；不配就用约定值，省得每个环境都要写三行
        originPrefix = blankToDefault(originPrefix, "originals");
        webpPrefix = blankToDefault(webpPrefix, "webp");
        thumbPrefix = blankToDefault(thumbPrefix, "thumbs");
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** 去掉结尾斜杠：拼路径时 {@code endpoint + "/" + bucket} 会拼出 {@code //}，签名对不上。 */
    private static String trimTrailingSlash(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
