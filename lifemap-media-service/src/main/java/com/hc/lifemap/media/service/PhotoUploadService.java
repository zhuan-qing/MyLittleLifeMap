package com.hc.lifemap.media.service;

import com.hc.lifemap.common.api.ErrorCode;
import com.hc.lifemap.media.dto.PhotoVO;
import com.hc.lifemap.media.storage.S3Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 上传编排：校验 → 生成 key → 存原图 → 回签名地址。本卡不做转码（T03/T04 才加）。
 *
 * <p>校验顺序是"数量 → 大小 → 类型 → 内容"：能早拒的早拒，别为 30MB 的文件先读进内存再嫌它大。</p>
 */
@Service
public class PhotoUploadService {

    private static final Logger log = LoggerFactory.getLogger(PhotoUploadService.class);

    /** 与卡片口径一致：单次 9 张、单张 20MB、三种类型。 */
    private static final int MAX_FILES = 9;
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/heic");

    private static final DateTimeFormatter MONTH_PATH = DateTimeFormatter.ofPattern("yyyy/MM");

    private final S3Storage storage;

    public PhotoUploadService(S3Storage storage) {
        this.storage = storage;
    }

    public List<PhotoVO> upload(long placeId, MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new PhotoRejected(ErrorCode.BAD_REQUEST, "没有收到文件，字段名应为 files");
        }
        if (files.length > MAX_FILES) {
            throw new PhotoRejected(ErrorCode.BAD_REQUEST, "单次最多 " + MAX_FILES + " 张，收到 " + files.length + " 张");
        }
        List<PhotoVO> result = new ArrayList<>(files.length);
        for (MultipartFile file : files) {
            result.add(uploadOne(placeId, file));
        }
        return result;
    }

    private PhotoVO uploadOne(long placeId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new PhotoRejected(ErrorCode.BAD_REQUEST, "存在空文件");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new PhotoRejected(ErrorCode.PAYLOAD_TOO_LARGE,
                    "单张不超过 20MB，当前 " + (file.getSize() / 1024 / 1024) + "MB");
        }

        String contentType = normalize(file.getContentType());
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new PhotoRejected(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "只支持 jpeg / png / heic，当前 " + (contentType == null ? "未知" : contentType));
        }

        byte[] bytes = readBytes(file);
        int[] size = readDimensions(bytes, contentType);

        String key = "originals/" + LocalDate.now().format(MONTH_PATH) + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + extensionOf(contentType);
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, contentType);
        String url = storage.presignedGet(key);

        log.info("照片已上传: placeId={} key={} bytes={} type={}", placeId, key, bytes.length,
                contentType);
        return new PhotoVO(null, url, url,
                size == null ? null : size[0], size == null ? null : size[1]);
    }

    /**
     * 内容探测：{@code getContentType()} 由客户端给、可伪造，所以真图要能被 ImageIO 解出来。
     *
     * <p>HEIC 例外：JDK 与当前依赖都解不了 HEIC，硬套这条会把手机原图全部判死。
     * 它的宽高留空，等 T03/T04 接上解码器再补——宁可少两个字段，也不能拒掉合法照片。</p>
     */
    private int[] readDimensions(byte[] bytes, String contentType) {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                if ("image/heic".equals(contentType)) {
                    log.info("HEIC 暂不解码，宽高留空（待 T03/T04 补解码器）");
                    return null;
                }
                throw new PhotoRejected(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "文件内容不是可解析的图片");
            }
            return new int[]{image.getWidth(), image.getHeight()};
        } catch (IOException e) {
            throw new PhotoRejected(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "图片读取失败: " + e.getMessage());
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new PhotoRejected(ErrorCode.INTERNAL_ERROR, "读取上传文件失败");
        }
    }

    private String normalize(String contentType) {
        return contentType == null ? null : contentType.trim().toLowerCase(Locale.ROOT);
    }

    private String extensionOf(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/heic" -> "heic";
            default -> "jpg";
        };
    }

    /** 业务拒绝：带上错误码，由控制器转成 {@code code != 0} 的 HTTP 200 响应。 */
    public static class PhotoRejected extends RuntimeException {

        private final transient ErrorCode errorCode;

        public PhotoRejected(ErrorCode errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public ErrorCode errorCode() {
            return errorCode;
        }
    }
}
