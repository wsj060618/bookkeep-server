package com.bookkeep.service.support;

import com.bookkeep.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 票据落盘规则的白盒单测（纯逻辑，不起 Spring）。
 * <p>这里盯的是「一旦写错就会出安全事故」的三件事：魔数识别、目录名安全化、路径越界。
 */
class ReceiptStorageTest {

    private final ReceiptStorage storage = new ReceiptStorage("./target/test-receipts-unit");

    @Test
    @DisplayName("魔数识别：jpg / png / webp 都能认；文本与 SVG 一律拒绝")
    void detectByMagicNumber() {
        assertEquals(ReceiptStorage.ImageType.JPEG, ReceiptStorage.detect(jpeg()));
        assertEquals(ReceiptStorage.ImageType.PNG, ReceiptStorage.detect(png()));
        assertEquals(ReceiptStorage.ImageType.WEBP, ReceiptStorage.detect(webp()));

        assertThrows(BusinessException.class, () -> ReceiptStorage.detect(new byte[4]),
                "太短的文件应直接拒");
        assertThrows(BusinessException.class,
                () -> ReceiptStorage.detect("<svg onload=alert(1)></svg>".getBytes()),
                "SVG 能带脚本，必须当「不是图片」拒掉");
        assertThrows(BusinessException.class,
                () -> ReceiptStorage.detect(new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '7', ' ', ' ', ' '}));
    }

    @Test
    @DisplayName("目录名：去掉非法字符 / 首尾点，拼上 bookId；全非法时回落 book_{id}")
    void bookDirNameIsSafe() {
        assertEquals("计科2301班费_12", ReceiptStorage.bookDirName(12L, "计科2301班费"));
        // Windows 最严：\ / : * ? " < > | 与控制字符都不能出现；非法字符直接删掉（不留奇怪的 _）
        assertEquals("abc_7", ReceiptStorage.bookDirName(7L, "a/b\\c:*?\"<>|"));
        // 「../」这类相对路径片段不能留下点号
        String traversal = ReceiptStorage.bookDirName(9L, "../../etc");
        assertTrue(traversal.endsWith("_9"), traversal);
        assertTrue(!traversal.startsWith(".") && !traversal.contains(".."), traversal);
        assertEquals("book_5", ReceiptStorage.bookDirName(5L, "///"));
        assertEquals("book_6", ReceiptStorage.bookDirName(6L, null));
        assertTrue(ReceiptStorage.bookDirName(8L, "字".repeat(60)).length() <= 31 + 2);
    }

    @Test
    @DisplayName("相对路径：账本目录/yyyy-MM/uuid.ext，月份取流水日期")
    void relativePathUsesTransactionMonth() {
        String path = ReceiptStorage.relativePath("账本_1", LocalDate.of(2026, 3, 9), "png");
        assertTrue(path.startsWith("账本_1/2026-03/"), path);
        assertTrue(path.endsWith(".png"), path);
        String name = path.substring(path.lastIndexOf('/') + 1);
        assertEquals(32 + 4, name.length(), "uuid 去横线后 32 位 + .png");

        // 同一张图连传两次不能撞名
        String other = ReceiptStorage.relativePath("账本_1", LocalDate.of(2026, 3, 9), "png");
        assertTrue(!path.equals(other), "每次都要生成新的 uuid");
    }

    @Test
    @DisplayName("默认标题：去扩展名、去非法字符、空则「票据」、超 30 字截断")
    void defaultTitleRules() {
        assertEquals("超市小票", ReceiptStorage.defaultTitle("超市小票.jpg"));
        assertEquals("2026-11-05 班服收据", ReceiptStorage.defaultTitle("2026-11-05 班服收据.png"));
        assertEquals("a b", ReceiptStorage.defaultTitle("a/b.jpg"));
        assertEquals("票据", ReceiptStorage.defaultTitle(".jpg"));
        assertEquals("票据", ReceiptStorage.defaultTitle(null));
        assertEquals(30, ReceiptStorage.defaultTitle("字".repeat(50) + ".jpg").length());
    }

    @Test
    @DisplayName("路径越界：库里的相对路径一旦带 .. 也解析不出去（最后一道闸）")
    void resolveBlocksTraversal() {
        assertThrows(BusinessException.class, () -> storage.resolve("../../etc/passwd"));
        assertThrows(BusinessException.class, () -> storage.resolve("../outside.png"));
        // 正常的相对路径能解析到根目录之下
        assertTrue(storage.resolve("账本_1/2026-03/x.png").startsWith(storage.root()));
    }

    /* ---------- 图片字节 ---------- */

    private static byte[] jpeg() {
        byte[] bytes = new byte[16];
        bytes[0] = (byte) 0xFF;
        bytes[1] = (byte) 0xD8;
        bytes[2] = (byte) 0xFF;
        return bytes;
    }

    private static byte[] png() {
        byte[] bytes = new byte[16];
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

    private static byte[] webp() {
        byte[] bytes = new byte[16];
        byte[] header = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
        System.arraycopy(header, 0, bytes, 0, header.length);
        return bytes;
    }
}