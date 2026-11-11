package com.hc.lifemap.media.image;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.MetadataException;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.TimeZone;

/**
 * 从图片字节里读 EXIF：方向（1–8）、拍摄时间、EXIF 记录的宽高。
 *
 * <p>只读不写——{@code metadata-extractor} 本身也不支持写回，而这个项目的原则是原图永不修改，
 * 方向只在生成派生图时应用一次（P2-T04）。</p>
 *
 * <p>任何读取失败都退化成 {@link ExifMeta#none()}：EXIF 属于"锦上添花"的元数据，
 * 缺了最多是照片方向不对，不该让整次上传失败。</p>
 */
@Component
public class ExifReader {

    private static final Logger log = LoggerFactory.getLogger(ExifReader.class);

    public ExifMeta read(byte[] imageBytes) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(imageBytes));
            ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            ExifSubIFDDirectory sub = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);

            Integer orientation = intOrNull(ifd0, ExifDirectoryBase.TAG_ORIENTATION);
            // EXIF 的 DateTimeOriginal 不带时区，是"墙钟时间"。必须显式按本机时区解释——
            // 无参的 getDateOriginal() 会把它当 UTC，拍于 14:30 的照片会变成 22:30。
            Date taken = sub == null ? null : sub.getDateOriginal(TimeZone.getDefault());

            return new ExifMeta(orientation == null ? ExifMeta.NORMAL : orientation,
                    taken == null ? null : OffsetDateTime.ofInstant(taken.toInstant(), ZoneId.systemDefault()),
                    intOrNull(sub, ExifDirectoryBase.TAG_EXIF_IMAGE_WIDTH),
                    intOrNull(sub, ExifDirectoryBase.TAG_EXIF_IMAGE_HEIGHT));
        } catch (Exception e) {
            // 不是图片、容器损坏、字段类型不对，都走这里
            log.debug("EXIF 读取失败，按无 EXIF 处理: {}", e.toString());
            return ExifMeta.none();
        }
    }

    private Integer intOrNull(Directory directory, int tag) {
        if (directory == null || !directory.containsTag(tag)) {
            return null;
        }
        try {
            return directory.getInt(tag);
        } catch (MetadataException e) {
            return null;
        }
    }
}
