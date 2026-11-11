package com.bookkeep.service.support;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 票据缩略图：最长边 480 的 JPEG（q0.78）。
 * <p>为什么要它：云服务器出网只有 2Mbps，一张 2.3MB 的手机原图要 10 秒，
 * 缩到 45KB 后同样的管道能同时喂几十个人（PLAN §10.9）。
 * <p>三条兜底原则：**解码不了就返回 null**（调用方退回原图，绝不 500）；
 * **缩出来比原图还大就不要**（本来就很小的图）；**纯内存操作**，不落临时文件。
 */
@Slf4j
@Component
public class ReceiptThumbnailer {

    /**
     * 480 够画廊卡片看，再大就失去省流量的意义。
     * <p><b>按宽度缩，不是按长边缩</b>：手机长截图（聊天记录、长小票）可能是 1084×17703，
     * 按长边缩到 480 会变成 29×480 的竖条，卡片上糊成一坨 —— 2026-11-12 真实踩到过。
     */
    public static final int MAX_WIDTH = 480;

    /** 长图的缩略图只保留顶部这一段（再长也没人会在卡片里看，截上头像 IM 的消息列表） */
    public static final int MAX_HEIGHT = 1440;

    private static final float QUALITY = 0.78f;

    static {
        // ImageIO 默认把解码缓存写到系统临时目录：缩略图全在内存里，没必要一图一个临时文件
        ImageIO.setUseCache(false);
    }

    public byte[] render(byte[] source) {
        if (source == null || source.length == 0) {
            return null;
        }
        BufferedImage decoded = decode(source);
        if (decoded == null) {
            return null;
        }
        byte[] jpeg = encode(orient(normalise(decoded), orientation(source)));
        // 原图本身就很小时缩略图反而更大，那就别用它
        return jpeg != null && jpeg.length < source.length ? jpeg : null;
    }

    /* ---------- 解码 / 缩放 / 旋转 / 编码 ---------- */

    private static BufferedImage decode(byte[] source) {
        try (var in = new ByteArrayInputStream(source);
             var iis = ImageIO.createImageInputStream(in)) {
            if (iis == null) {
                return null;
            }
            var readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return null;
            }
            var reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            log.debug("图片解码失败，不生成缩略图：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 缩略图规格：**宽度 ≤480**，高度超过 1440 就只截顶部那一段（长截图/长小票），
     * 统一成 RGB + 白底（PNG 的透明区直接画到 RGB 上会是黑的）。从不放大。
     */
    private static BufferedImage normalise(BufferedImage source) {
        int w = source.getWidth();
        int h = source.getHeight();
        double ratio = Math.min(1.0, (double) MAX_WIDTH / w);
        int tw = Math.max(1, (int) Math.round(w * ratio));
        int th = Math.max(1, (int) Math.round(h * ratio));
        int visibleHeight = Math.min(th, MAX_HEIGHT);
        // 只画源图顶部 visibleHeight/ratio 那一截
        double srcHeight = Math.min(h, visibleHeight / ratio);

        BufferedImage out = new BufferedImage(tw, visibleHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, tw, visibleHeight);
            g.drawImage(source, 0, 0, tw, visibleHeight,
                    0, 0, w, (int) Math.round(srcHeight), null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** EXIF Orientation：手机竖拍的像素其实是横的，只有这一位在说「要转」，ImageIO 自己不认 */
    private static int orientation(byte[] source) {
        try {
            var exif = ImageMetadataReader.readMetadata(new ByteArrayInputStream(source))
                    .getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (exif == null || !exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
                return 1;
            }
            int value = exif.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            return value >= 1 && value <= 8 ? value : 1;
        } catch (Exception e) {
            return 1; // 没有 EXIF（png/webp/截图）就是正的
        }
    }

    /** 八种方向：2/4 是镜像，5/7 是对角翻转（换宽高），3/6/8 是旋转 */
    private static BufferedImage orient(BufferedImage source, int orientation) {
        if (orientation <= 1) {
            return source;
        }
        int w = source.getWidth();
        int h = source.getHeight();
        boolean swap = orientation >= 5;
        int tw = swap ? h : w;
        int th = swap ? w : h;

        int[] src = source.getRGB(0, 0, w, h, null, 0, w);
        int[] dst = new int[w * h];
        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                int sx = switch (orientation) {
                    case 2, 3 -> w - 1 - x;
                    case 4 -> x;
                    case 5, 6 -> y;
                    default -> w - 1 - y; // 7、8
                };
                int sy = switch (orientation) {
                    case 2 -> y;
                    case 3, 4 -> h - 1 - y;
                    case 5 -> x;
                    case 6, 7 -> h - 1 - x;
                    default -> x; // 8
                };
                dst[y * tw + x] = src[sy * w + sx];
            }
        }
        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        out.setRGB(0, 0, tw, th, dst, 0, tw);
        return out;
    }

    private static byte[] encode(BufferedImage image) {
        var writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return null;
        }
        var writer = writers.next();
        var param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(QUALITY);
        try (var out = new ByteArrayOutputStream(32 * 1024);
             var ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
            ios.flush();
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            log.warn("票据缩略图编码失败：{}", e.getMessage());
            return null;
        } finally {
            writer.dispose();
        }
    }
}
