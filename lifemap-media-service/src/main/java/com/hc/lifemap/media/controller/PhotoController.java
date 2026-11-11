package com.hc.lifemap.media.controller;

import com.hc.lifemap.common.api.ApiResponse;
import com.hc.lifemap.common.api.ErrorCode;
import com.hc.lifemap.media.dto.PhotoVO;
import com.hc.lifemap.media.image.ImagePipeline;
import com.hc.lifemap.media.service.PhotoCommandService;
import com.hc.lifemap.media.service.PhotoUploadService;
import com.hc.lifemap.media.storage.S3Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 照片入口：上传 {@code POST /api/places/{id}/photos}、删除 {@code DELETE /api/photos/{id}}。
 *
 * <p>没有类级 {@code @RequestMapping}：两个接口的路径前缀不同（{@code /api/places} 与
 * {@code /api/photos}），写在方法上比在类上开前缀再覆盖更不容易看错。</p>
 *
 * <p>地点是否存在不在这里校验——跨服务查 place 会把媒体链路卡在别人的可用性上。</p>
 */
@RestController
public class PhotoController {

    private static final Logger log = LoggerFactory.getLogger(PhotoController.class);

    private final PhotoUploadService uploadService;
    private final PhotoCommandService commandService;

    public PhotoController(PhotoUploadService uploadService, PhotoCommandService commandService) {
        this.uploadService = uploadService;
        this.commandService = commandService;
    }

    @PostMapping(path = "/api/places/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<PhotoVO>> upload(@PathVariable("id") long placeId,
                                             @RequestParam(name = "files", required = false) MultipartFile[] files) {
        if (placeId <= 0) {
            return ApiResponse.fail(ErrorCode.BAD_REQUEST, "placeId 必须为正整数");
        }
        return ApiResponse.ok(uploadService.upload(placeId, files));
    }

    /** 幂等：重复删、删不存在的 id 都返回成功，与 P1-T09 的地点软删一致。 */
    @DeleteMapping("/api/photos/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") long photoId) {
        if (photoId <= 0) {
            return ApiResponse.fail(ErrorCode.BAD_REQUEST, "photoId 必须为正整数");
        }
        commandService.delete(photoId);
        return ApiResponse.ok();
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

    @ExceptionHandler(ImagePipeline.ImagePipelineException.class)
    public ApiResponse<Void> onPipelineError(ImagePipeline.ImagePipelineException e) {
        // 走到这里说明类型过了但字节不是真图（改名、损坏），口径与 10415 一致
        log.warn("图片处理失败: {}", e.getMessage());
        return ApiResponse.fail(ErrorCode.UNSUPPORTED_MEDIA_TYPE, "文件内容不是可解析的图片");
    }

    @ExceptionHandler(S3Storage.StorageException.class)
    public ApiResponse<Void> onStorageError(S3Storage.StorageException e) {
        log.error("对象存储失败: {}", e.getMessage(), e);
        return ApiResponse.fail(ErrorCode.STORAGE_ERROR);
    }
}
