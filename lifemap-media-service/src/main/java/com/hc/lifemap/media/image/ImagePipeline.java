package com.hc.lifemap.media.image;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * 图片管线：按 EXIF 方向摆正 → 等比缩到目标长边 → 编码。两条产线，原图永不修改。
 *
 * <p>口径（来自桌面布局反推）：详情大图长边 2048、WebP q=0.82；缩略图长边 480、q=0.78。
 * 只缩不放——原图比目标还小时保持原样，放大只会白白变糊。</p>
 *
 * <p>WebP 靠 {@code webp-imageio} 插件，JDK 自带编码器里没有它。插件缺失或编码抛错时降级为
 * JPEG q=0.85：宁可多占点体积，也不能让整次上传因为一个编码器失败。</p>
 */
@Component
public class ImagePipeline {

    private static final Logger log = LoggerFactory.getLogger(ImagePipeline.class);

    private static final int DETAIL_MAX_EDGE = 2048;
    private static final float DETAIL_QUALITY = 0.82f;
    private static final int THUMB_MAX_EDGE = 480;
    private static final float THUMB_QUALITY = 0.78f;
    private static final float JPEG_FALLBACK_QUALITY = 0.85f;

    /** 一张产物的字节、真实类型与尺寸。类型要跟着实际编码器走，不能写死 image/webp。 */
    public record Rendered(byte[] bytes, String contentType, String extension, int width, int height) {
    }

    /** 两条产线 + 原图摆正后的尺寸（落库用这个，不是派生图的尺寸）。 */
    public record Derived(Rendered detail, Rendered thumbnail, int width, int height) {
    }

    /** 启动自检：没有 WebP writer 时上传仍然能跑，但会一直降级，值得在启动日志里喊一声。 */
    @PostConstruct
    void checkEncoder() {
        if (ImageIO.getImageWritersByFormatName("webp").hasNext()) {
            log.info("WebP 编码器就绪，派生图按 webp q={} / {} 输出", DETAIL_QUALITY, THUMB_QUALITY);
        } else {
            log.warn("未找到 WebP 编码器，派生图将降级为 JPEG q={}", JPEG_FALLBACK_QUALITY);
        }
    }

    public Derived derive(byte[] original, ExifMeta meta) {
        BufferedImage upright = applyOrientation(decode(original), meta.orientation());
        return new Derived(
                render(upright, DETAIL_MAX_EDGE, DETAIL_QUALITY),
                render(upright, THUMB_MAX_EDGE, THUMB_QUALITY),
                upright.getWidth(), upright.getHeight());
    }

    private BufferedImage decode(byte[] bytes) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                throw new ImagePipelineException("原图无法解码");
            }
            return image;
        } catch (IOException e) {
            throw new ImagePipelineException("原图解码失败: " + e.getMessage());
        }
    }

    /**
     * EXIF 的 8 种方向：1 正常；2/4 水平/垂直镜像；3 转 180°；5–8 带 90° 旋转且长宽互换。
     * 这些变换是像素重排，用最近邻就够了——插值只会把边缘糊掉。
     */
    private BufferedImage applyOrientation(BufferedImage src, int orientation) {
        if (orientation == ExifMeta.NORMAL) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        boolean swap = orientation >= 5;
        AffineTransform tx = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);
            default -> new AffineTransform();
        };
        // 用 Graphics2D 而不是 AffineTransformOp：JPEG 解码出来常是 TYPE_3BYTE_BGR，
        // 直接 filter 到 TYPE_INT_RGB 会抛 "Unable to transform src image"，绘制则会自动转色彩模型
        BufferedImage dst = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.drawImage(src, tx, null);
        g.dispose();
        return dst;
    }

    private Rendered render(BufferedImage upright, int maxEdge, float quality) {
        BufferedImage scaled = scale(upright, maxEdge);
        byte[] webp = encode(scaled, "webp", quality);
        if (webp != null) {
            return new Rendered(webp, "image/webp", "webp", scaled.getWidth(), scaled.getHeight());
        }
        byte[] jpeg = encode(scaled, "jpeg", JPEG_FALLBACK_QUALITY);
        if (jpeg == null) {
            throw new ImagePipelineException("既没有 WebP 也没有 JPEG 编码器");
        }
        return new Rendered(jpeg, "image/jpeg", "jpg", scaled.getWidth(), scaled.getHeight());
    }

    /**
     * 长边缩到 maxEdge，短边等比。一步从 4000 缩到 480 会有锯齿，所以先逐级减半再收尾——
     * 这是不引入 Scalr 之类依赖的前提下最省事的高质量做法。
     */
    private BufferedImage scale(BufferedImage src, int maxEdge) {
        int longEdge = Math.max(src.getWidth(), src.getHeight());
        if (longEdge <= maxEdge) {
            return src;
        }
        double ratio = (double) maxEdge / longEdge;
        int targetW = Math.max(1, (int) Math.round(src.getWidth() * ratio));
        int targetH = Math.max(1, (int) Math.round(src.getHeight() * ratio));

        BufferedImage current = src;
        while (current.getWidth() >= targetW * 2 && current.getHeight() >= targetH * 2) {
            current = drawScaled(current, current.getWidth() / 2, current.getHeight() / 2);
        }
        return drawScaled(current, targetW, targetH);
    }

    private BufferedImage drawScaled(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    /** 编码成指定格式；没有对应 writer 或编码抛错时返回 null，由调用方决定降级到哪一档。 */
    private byte[] encode(BufferedImage image, String format, float quality) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(format);
        if (!writers.hasNext()) {
            return null;
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                // WebP 插件不先选压缩类型就设质量会抛 "No compression type set!"
                if ("webp".equals(format)) {
                    param.setCompressionType("Lossy");
                }
                param.setCompressionQuality(quality);
            }
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
            ios.flush();
            return bos.toByteArray();
        } catch (Exception e) {
            log.warn("{} 编码失败，尝试降级: {}", format, e.toString());
            return null;
        } finally {
            writer.dispose();
        }
    }

    /** 管线内部失败：原图解不开、编码器全挂。调用方按 10415 或 50000 处理。 */
    public static class ImagePipelineException extends RuntimeException {

        public ImagePipelineException(String message) {
            super(message);
        }
    }
}
