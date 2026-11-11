package com.hc.lifemap.media.storage;

import com.hc.lifemap.media.config.S3Properties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对象 key → 可渲染 URL。签名有成本、又要每次刷新详情都重签，所以按 key 缓存。
 *
 * <p>缓存的有效期不是签名的有效期，而是"签名过期前 5 分钟"：卡在临界点上返回一个还剩 3 秒的
 * 地址，浏览器拿到就已经 403 了。提前 5 分钟换新签名，前端永远拿到能用的链接。</p>
 *
 * <p>进程内缓存即可——单机、单用户，重启后重新签一遍没有任何代价，不值得上 Redis。</p>
 */
@Component
public class PhotoUrlResolver {

    /** 提前多久视为过期：够一次页面加载，也够慢网络下把图下完。 */
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

    /** 缓存条目上限，超过就顺手清掉已经过期的，避免长时间运行后无限增长。 */
    private static final int MAX_ENTRIES = 2000;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private final S3Storage storage;
    private final S3Properties properties;

    public PhotoUrlResolver(S3Storage storage, S3Properties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    /** key 为空时返回 null：调用方不用为"这张图没有缩略图"写分支。 */
    public String resolve(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        Instant now = Instant.now();
        Cached cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(now.plus(REFRESH_MARGIN))) {
            return cached.url();
        }

        String url = storage.presignedGet(key);
        cache.put(key, new Cached(url, now.plus(properties.presignTtl())));
        evictIfCrowded(now);
        return url;
    }

    private void evictIfCrowded(Instant now) {
        if (cache.size() > MAX_ENTRIES) {
            cache.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        }
    }

    private record Cached(String url, Instant expiresAt) {
    }
}
