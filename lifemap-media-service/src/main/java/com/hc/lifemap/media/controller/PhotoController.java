package com.hc.lifemap.media.controller;

import com.hc.lifemap.common.api.ApiResponse;
import com.hc.lifemap.common.api.ErrorCode;
import com.hc.lifemap.media.dto.PhotoVO;
import com.hc.lifemap.media.service.PhotoUploadService;
import com.hc.lifemap.media.storage.S3Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 照片上传入口：{@code POST /api/places/{id}/photos}，字段名 {@code files}，单次 1–9 张。
 *
 * <p>地点是否存在不在本卡校验——跨服务查 place 会把媒体链路卡在别人的可用性上，
 * 真要强校验等 T05 落库时再定。</p>
 */
@RestController
@RequestMapping("/api/places")
public class PhotoController {

    private static final Logger log = LoggerFactory.getLogger(PhotoController.class);

    private final PhotoUploadService uploadService;

    public PhotoController(PhotoUploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping(path = "/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<PhotoVO>> upload(@PathVariable("id") long placeId,
                                             @RequestParam(name = "files", required = false) MultipartFile[] files) {
        if (placeId <= 0) {
            return ApiResponse.fail(ErrorCode.BAD_REQUEST, "placeId 必须为正整数");
        }
        return ApiResponse.ok(uploadService.upload(placeId, files));
    }

    /*
     * 错误出口暂时挂在控制器上：本卡只有这一个写接口，等 T05/T06 多起来再提成 @RestControllerAdvice。
     * 业务错误一律 HTTP 200 + code != 0，与 place-service 口径一致。
     */
    @ExceptionHandler(PhotoUploadService.PhotoRejected.class)
    public ApiResponse<Void> onRejected(PhotoUploadService.PhotoRejected e) {
        log.warn("上传被拒: {} {}", e.errorCode().code(), e.getMessage());
        return ApiResponse.fail(e.errorCode(), e.getMessage());
    }

    @ExceptionHandler(S3Storage.StorageException.class)
    public ApiResponse<Void> onStorageError(S3Storage.StorageException e) {
        log.error("对象存储失败: {}", e.getMessage(), e);
        return ApiResponse.fail(ErrorCode.STORAGE_ERROR);
    }
}
