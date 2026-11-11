package com.hc.lifemap.media.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hc.lifemap.media.entity.Photo;
import com.hc.lifemap.media.mapper.PhotoMapper;
import com.hc.lifemap.media.storage.S3Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 照片删除：软删记录 + 删三个对象。
 *
 * <p>顺序是先改库再删对象：反过来的话，对象删成功、事务回滚，库里就留下一条指向空地址的记录，
 * 页面直接裂图。对象删除失败只记日志——对象是可以事后对账清的，记录已经被标记才是用户要的结果。</p>
 *
 * <p>幂等：删不存在的 id、删已删的 id 都直接成功。前端重试、双击删除、网络重发都不该报错，
 * 与 P1-T09 的地点软删语义保持一致。</p>
 */
@Service
public class PhotoCommandService {

    private static final Logger log = LoggerFactory.getLogger(PhotoCommandService.class);

    private final PhotoMapper photoMapper;
    private final S3Storage storage;

    public PhotoCommandService(PhotoMapper photoMapper, S3Storage storage) {
        this.photoMapper = photoMapper;
        this.storage = storage;
    }

    @Transactional
    public void delete(long photoId) {
        Photo photo = photoMapper.selectById(photoId);
        if (photo == null || photo.getDeletedAt() != null) {
            return;
        }

        // 条件里再判一次 isNull(deletedAt)：先查后改之间被并发删掉时影响 0 行，据此跳过删对象，
        // 免得两个请求各删一遍同一批对象
        int affected = photoMapper.update(null, Wrappers.lambdaUpdate(Photo.class)
                .eq(Photo::getId, photoId)
                .isNull(Photo::getDeletedAt)
                .set(Photo::getDeletedAt, OffsetDateTime.now()));
        if (affected == 0) {
            return;
        }

        removeQuietly(photo.getObjectKey());
        removeQuietly(photo.getWebpKey());
        removeQuietly(photo.getThumbKey());
        log.info("照片已软删: id={} placeId={}", photoId, photo.getPlaceId());
    }

    /** 单个对象删失败不影响其余两个，也不影响已提交的软删——留给 P6-T03 的对账任务收尾。 */
    private void removeQuietly(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            storage.remove(key);
        } catch (Exception e) {
            log.warn("对象删除失败，留给对账清理: key={} err={}", key, e.toString());
        }
    }
}
