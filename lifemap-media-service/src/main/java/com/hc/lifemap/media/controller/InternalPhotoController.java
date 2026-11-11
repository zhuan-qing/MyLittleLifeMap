package com.hc.lifemap.media.controller;

import com.hc.lifemap.media.dto.PhotoVO;
import com.hc.lifemap.media.entity.Photo;
import com.hc.lifemap.media.mapper.PhotoMapper;
import com.hc.lifemap.media.storage.PhotoUrlResolver;
import com.hc.lifemap.media.storage.S3Storage;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 只对内网的读取接口：place 详情一次拿全照片，导出时按 key 流式取字节。
 *
 * <p>不经过网关、不做签权——调用方是同一个进程外部的可信服务（dev_doc §4.2 的 `/internal/**`）。
 * 返回体也不套 {@code ApiResponse}：这是服务之间的数据契约，不是给前端看的。</p>
 */
@RestController
@RequestMapping("/internal/photos")
public class InternalPhotoController {

    /** 一次最多 200 个地点：再多就是调用方写错了（详情页不会一次开 200 个）。 */
    private static final int MAX_IDS = 200;

    /** 只允许读照片三类前缀，避免内部接口被拿来当"任意对象下载器"。 */
    private static final Set<String> ALLOWED_PREFIXES = Set.of("originals/", "webp/", "thumbs/");

    private final PhotoMapper photoMapper;
    private final PhotoUrlResolver urlResolver;
    private final S3Storage storage;

    public InternalPhotoController(PhotoMapper photoMapper, PhotoUrlResolver urlResolver, S3Storage storage) {
        this.photoMapper = photoMapper;
        this.urlResolver = urlResolver;
        this.storage = storage;
    }

    /**
     * {@code ?ids=1,2,3} → {@code {"1": [PhotoVO...], "2": [...]}}。空 ids 直接返回空 map，不发 SQL。
     */
    @GetMapping("/by-place-ids")
    public Map<Long, List<PhotoVO>> byPlaceIds(@RequestParam(name = "ids", required = false) List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        if (ids.size() > MAX_IDS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ids 最多 " + MAX_IDS + " 个，收到 " + ids.size());
        }
        // 保留插入顺序：前端按详情页的地点顺序渲染，不希望每次请求顺序都变
        return photoMapper.selectByPlaceIds(ids).stream()
                .collect(Collectors.groupingBy(Photo::getPlaceId, LinkedHashMap::new,
                        Collectors.mapping(this::toVo, Collectors.toList())));
    }

    /** {@code ?key=originals/2026/11/xxx.jpg} → 二进制流，导出打包时直接接 {@code ZipOutputStream}。 */
    @GetMapping("/stream")
    public ResponseEntity<StreamingResponseBody> stream(@RequestParam("key") String key) {
        String normalized = key == null ? "" : key.trim();
        if (ALLOWED_PREFIXES.stream().noneMatch(normalized::startsWith)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "key 必须以 originals/ 、webp/ 或 thumbs/ 开头");
        }
        // 边读边写，不把整张原图读进堆——导出的图可能是几十 MB 一张
        StreamingResponseBody body = out -> {
            try (InputStream in = storage.get(normalized)) {
                in.transferTo(out);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(body);
    }

    private PhotoVO toVo(Photo photo) {
        return new PhotoVO(photo.getId(), urlResolver.resolve(photo.getThumbKey()),
                urlResolver.resolve(photo.getWebpKey()), photo.getWidth(), photo.getHeight());
    }
}
