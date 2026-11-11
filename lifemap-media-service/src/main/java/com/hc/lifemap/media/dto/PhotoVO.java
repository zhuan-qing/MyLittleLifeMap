package com.hc.lifemap.media.dto;

/**
 * 上传结果。{@code id} 要等 P2-T05 落库后才有，本卡返回 null。
 *
 * <p>{@code thumbUrl} / {@code webpUrl} 现在是原图的预签名地址——T03/T04 做出派生图后
 * 才换成 {@code thumbs/}、{@code webp/} 下的对象，前端字段名不用改。</p>
 */
public record PhotoVO(Long id, String thumbUrl, String webpUrl, Integer width, Integer height) {
}
