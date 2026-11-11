package com.hc.lifemap.media.service;

import com.hc.lifemap.common.api.ErrorCode;
import com.hc.lifemap.media.config.S3Properties;
import com.hc.lifemap.media.dto.PhotoVO;
import com.hc.lifemap.media.entity.Photo;
import com.hc.lifemap.media.image.ExifMeta;
import com.hc.lifemap.media.image.ExifReader;
import com.hc.lifemap.media.image.ImagePipeline;
import com.hc.lifemap.media.mapper.PhotoMapper;
import com.hc.lifemap.media.storage.PhotoUrlResolver;
import com.hc.lifemap.media.storage.S3Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 上传编排：校验 → 派生 → 三个对象落桶 → 写 photo 表 → 回可渲染地址。
 *
 * <p>顺序是有讲究的：先派生再落盘，解不开的图不会在桶里留垃圾；先落对象再写库，
 * 库里出现行时对象一定已经可读——反过来会出现"有记录但图 404"。</p>
 *
 * <p>校验顺序是"数量 → 大小 → 类型"：能早拒的早拒，别为 20MB 的文件先读进内存再嫌它大。</p>
 */
@Service
public class PhotoUploadService {

    private static final Logger log = LoggerFactory.getLogger(PhotoUploadService.class);

    /** 与卡片口径一致：单次 9 张、单张 20MB、三种类型。 */
    private static final int MAX_FILES = 9;
    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/heic");
    private static final String HEIC = "image/heic";

    private static final DateTimeFormatter MONTH_PATH = DateTimeFormatter.ofPattern("yyyy/MM");

    private final S3Storage storage;
    private final S3Properties properties;
    private final ExifReader exifReader;
    private final ImagePipeline pipeline;
    private final PhotoMapper photoMapper;
    private final PhotoUrlResolver urlResolver;

    public PhotoUploadService(S3Storage storage, S3Properties properties, ExifReader exifReader,
                              ImagePipeline pipeline, PhotoMapper photoMapper, PhotoUrlResolver urlResolver) {
        this.storage = storage;
        this.properties = properties;
        this.exifReader = exifReader;
        this.pipeline = pipeline;
        this.photoMapper = photoMapper;
        this.urlResolver = urlResolver;
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
        // 类型允许但解不了：没有 HEIC 解码器就出不了派生图，宽高也拿不到，落不了库。
        // 与其存一条半残记录（查回来没有缩略图），不如明确拒掉并告诉用户怎么办。
        if (HEIC.equals(contentType)) {
            throw new PhotoRejected(ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    "HEIC 暂不支持（缺解码器），请转成 JPEG 后上传");
        }

        byte[] bytes = readBytes(file);
        String base = LocalDate.now().format(MONTH_PATH) + "/" + UUID.randomUUID().toString().replace("-", "");

        // 先解码派生再落盘：解不开就到此为止，不在桶里留一张废图
        ExifMeta exif = exifReader.read(bytes);
        ImagePipeline.Derived derived = pipeline.derive(bytes, exif);

        String originKey = properties.originPrefix() + "/" + base + "." + extensionOf(contentType);
        String webpKey = properties.webpPrefix() + "/" + base + "." + derived.detail().extension();
        String thumbKey = properties.thumbPrefix() + "/" + base + "." + derived.thumbnail().extension();

        storage.put(originKey, new ByteArrayInputStream(bytes), bytes.length, contentType);
        storage.put(webpKey, new ByteArrayInputStream(derived.detail().bytes()),
                derived.detail().bytes().length, derived.detail().contentType());
        storage.put(thumbKey, new ByteArrayInputStream(derived.thumbnail().bytes()),
                derived.thumbnail().bytes().length, derived.thumbnail().contentType());

        Photo photo = new Photo();
        photo.setPlaceId(placeId);
        photo.setObjectKey(originKey);
        photo.setWebpKey(webpKey);
        photo.setThumbKey(thumbKey);
        photo.setWidth(derived.width());
        photo.setHeight(derived.height());
        // 存原图字节数，不是派生图——这列回答的是"这条记录占了多少磁盘"
        photo.setBytes((long) bytes.length);
        // EXIF 没有拍摄时间时回落为上传时间，免得列表里出现时间空白的记录
        photo.setTakenAt(exif.takenAt() == null ? OffsetDateTime.now() : exif.takenAt());
        photoMapper.insert(photo);

        log.info("照片已入库: id={} placeId={} {}x{} origin={}B webp={}B thumb={}B orientation={}",
                photo.getId(), placeId, derived.width(), derived.height(), bytes.length,
                derived.detail().bytes().length, derived.thumbnail().bytes().length, exif.orientation());

        return new PhotoVO(photo.getId(), urlResolver.resolve(thumbKey), urlResolver.resolve(webpKey),
                derived.width(), derived.height());
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
            case HEIC -> "heic";
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
