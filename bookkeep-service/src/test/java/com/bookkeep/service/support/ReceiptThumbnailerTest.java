package com.bookkeep.service.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缩略图的白盒单测：最长边 480、JPEG、EXIF 方向、解不开就退回 null。
 * <p>为什么要钉 EXIF：手机竖拍的像素是横的，浏览器会自动转、ImageIO 不会——
 * 不处理的话缩略图躺倒，而原图看着是正的，很容易被误判成"前端 bug"。
 */
class ReceiptThumbnailerTest {

    private final ReceiptThumbnailer thumbnailer = new ReceiptThumbnailer();

    @Test
    @DisplayName("横图：最长边缩到 480，编码成 JPEG 且字节数明显变小")
    void landscapeShrinksTo480() throws IOException {
        byte[] source = encode(scene(1600, 1200, true), "jpg");

        byte[] thumb = thumbnailer.render(source);

        assertTrue(thumb != null && thumb.length > 0, "应生成缩略图");
        assertTrue(thumb.length < source.length, "缩略图必须比原图小：" + thumb.length + " vs " + source.length);
        assertJpeg(thumb);
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(thumb));
        assertEquals(480, image.getWidth());
        assertEquals(360, image.getHeight());
    }

    @Test
    @DisplayName("竖图：长边才是 480（拿宽边算会框错方向）")
    void portraitUsesLongEdge() throws IOException {
        byte[] thumb = thumbnailer.render(encode(scene(400, 1200, true), "png"));

        assertTrue(thumb != null, "应生成缩略图");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(thumb));
        assertEquals(160, image.getWidth());
        assertEquals(480, image.getHeight());
    }

    @Test
    @DisplayName("EXIF Orientation=6：先缩再顺时针转 90°，宽高互换")
    void honoursExifOrientation6() throws IOException {
        // 左上角一块红，其余白：转完那块红应该跑到右上角
        byte[] source = withExifOrientation(encode(scene(1200, 600, false), "jpg"), 6);

        byte[] thumb = thumbnailer.render(source);

        assertTrue(thumb != null, "应生成缩略图");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(thumb));
        assertEquals(240, image.getWidth(), "1200x600 转 90° 后应该是竖的");
        assertEquals(480, image.getHeight());
        assertTrue(isRed(image.getRGB(image.getWidth() - 10, 10)), "红色块应转到右上角");
        assertTrue(isWhite(image.getRGB(10, 10)), "原左上角应转到右下，这里应该是白底");
    }

    @Test
    @DisplayName("解不开的字节（假图/异常编码）返回 null，让调用方退回原图")
    void undecodableReturnsNull() {
        assertNull(thumbnailer.render(new byte[]{1, 2, 3}));
        assertNull(thumbnailer.render(pngHeaderOnly()));
        assertNull(thumbnailer.render(new byte[0]));
        assertNull(thumbnailer.render(null));
    }

    @Test
    @DisplayName("本来就很小的图：缩出来反而更大，就不要缩略图")
    void tinyImageIsSkipped() throws IOException {
        BufferedImage solid = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = solid.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 64, 64);
        g.dispose();
        byte[] source = encode(solid, "png");

        assertTrue(source.length < 1024, "前提：原图很小（实际 " + source.length + " 字节）");
        assertNull(thumbnailer.render(source));
    }

    /* ---------- 工具 ---------- */

    /** 左上角 1/4 是红色，其余白色；{@code noise} 打开时撒噪点把 JPEG 撑大 */
    private static BufferedImage scene(int width, int height, boolean noise) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.RED);
        g.fillRect(0, 0, width / 4, height / 4);
        g.dispose();
        if (noise) {
            Random random = new Random(42);
            for (int y = 0; y < height; y += 4) {
                for (int x = 0; x < width; x += 4) {
                    image.setRGB(x, y, random.nextInt(0xFFFFFF));
                }
            }
        }
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    /** 往 JPEG 的 SOI 后面插一段最小 APP1/EXIF，只带 Orientation 一个 tag */
    private static byte[] withExifOrientation(byte[] jpeg, int orientation) {
        if (jpeg[0] != (byte) 0xFF || jpeg[1] != (byte) 0xD8) {
            throw new IllegalArgumentException("不是 JPEG");
        }
        byte[] tiff = {
                'I', 'I', 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,            // 小端 TIFF 头，IFD0 在偏移 8
                0x01, 0x00,                                              // 1 个条目
                0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00,           // tag 0x0112 / SHORT / 数量 1
                (byte) orientation, 0x00, 0x00, 0x00,                     // 值
                0x00, 0x00, 0x00, 0x00                                   // 没有下一个 IFD
        };
        byte[] exifHeader = {'E', 'x', 'i', 'f', 0x00, 0x00};
        int payload = exifHeader.length + tiff.length;
        byte[] segment = new byte[4 + payload];
        segment[0] = (byte) 0xFF;
        segment[1] = (byte) 0xE1;
        segment[2] = (byte) ((payload + 2) >> 8);
        segment[3] = (byte) (payload + 2);
        System.arraycopy(exifHeader, 0, segment, 4, exifHeader.length);
        System.arraycopy(tiff, 0, segment, 4 + exifHeader.length, tiff.length);

        byte[] out = new byte[jpeg.length + segment.length];
        System.arraycopy(jpeg, 0, out, 0, 2);
        System.arraycopy(segment, 0, out, 2, segment.length);
        System.arraycopy(jpeg, 2, out, 2 + segment.length, jpeg.length - 2);
        return out;
    }

    private static byte[] pngHeaderOnly() {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) 0x89;
        bytes[1] = 'P';
        bytes[2] = 'N';
        bytes[3] = 'G';
        bytes[4] = 0x0D;
        bytes[5] = 0x0A;
        bytes[6] = 0x1A;
        bytes[7] = 0x0A;
        return bytes;
    }

    private static void assertJpeg(byte[] bytes) {
        assertTrue(bytes.length > 3
                        && (bytes[0] & 0xFF) == 0xFF
                        && (bytes[1] & 0xFF) == 0xD8
                        && (bytes[2] & 0xFF) == 0xFF,
                "输出应是 JPEG 魔数");
    }

    private static boolean isRed(int rgb) {
        Color c = new Color(rgb);
        return c.getRed() > 200 && c.getGreen() < 60 && c.getBlue() < 60;
    }

    private static boolean isWhite(int rgb) {
        Color c = new Color(rgb);
        return c.getRed() > 200 && c.getGreen() > 200 && c.getBlue() > 200;
    }
}
