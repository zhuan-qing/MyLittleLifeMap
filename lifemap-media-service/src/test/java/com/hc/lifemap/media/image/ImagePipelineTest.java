package com.hc.lifemap.media.image;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 尺寸与体积断言：长边 2048 / 480，等比不裁切，体积在口径内。 */
class ImagePipelineTest {

    private final ImagePipeline pipeline = new ImagePipeline();

    @Test
    void landmarkPhotoProducesTwoValidDerivatives() throws Exception {
        byte[] source = photo(4000, 3000);
        ImagePipeline.Derived derived = pipeline.derive(source, ExifMeta.none());

        ImagePipeline.Rendered detail = derived.detail();
        ImagePipeline.Rendered thumb = derived.thumbnail();
        System.out.printf("source=%dKB detail=%dKB %dx%d thumb=%dKB %dx%d%n",
                source.length / 1024, detail.bytes().length / 1024, detail.width(), detail.height(),
                thumb.bytes().length / 1024, thumb.width(), thumb.height());

        assertEquals(2048, Math.max(detail.width(), detail.height()), "详情大图长边 2048");
        assertEquals(1536, Math.min(detail.width(), detail.height()), "短边等比，不裁切");
        assertEquals(480, Math.max(thumb.width(), thumb.height()), "缩略图长边 480");
        assertEquals("image/webp", detail.contentType());
        assertTrue(detail.bytes().length < 800 * 1024, "详情大图应小于 800KB");
        assertTrue(thumb.bytes().length < 80 * 1024, "缩略图应小于 80KB");
        assertEquals(4000, derived.width(), "落库用的是原图去方向后的尺寸");
        assertEquals(3000, derived.height());
    }

    @Test
    void portraitExifIsAppliedBeforeScaling() throws Exception {
        // 手机竖拍：像素是横的，靠 Orientation=6 告诉看图的人要顺时针转 90°
        byte[] source = photo(4000, 3000);
        ImagePipeline.Derived derived = pipeline.derive(source,
                new ExifMeta(6, OffsetDateTime.now(), 4000, 3000));

        System.out.printf("portrait: %dx%d -> detail %dx%d%n",
                derived.width(), derived.height(), derived.detail().width(), derived.detail().height());
        assertEquals(3000, derived.width(), "摆正后长宽互换");
        assertEquals(4000, derived.height());
        assertTrue(derived.detail().height() > derived.detail().width(), "竖拍照片的派生图必须是竖的");
        assertEquals(2048, Math.max(derived.detail().width(), derived.detail().height()));
    }

    /** 造一张"像照片"的图：渐变底 + 几块色块，纯色图压出来的体积没有参考价值。 */
    private byte[] photo(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        for (int y = 0; y < height; y++) {
            g.setColor(new Color(y * 255 / height, 90, 255 - y * 255 / height));
            g.drawLine(0, y, width, y);
        }
        g.setColor(new Color(240, 200, 60));
        g.fillRect(width / 8, height / 6, width / 3, height / 3);
        g.setColor(new Color(30, 60, 120));
        g.fillOval(width / 2, height / 2, width / 3, height / 4);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", bos);
        return bos.toByteArray();
    }
}
