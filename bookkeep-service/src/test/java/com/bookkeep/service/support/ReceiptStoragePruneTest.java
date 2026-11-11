package com.bookkeep.service.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 孤儿文件清理的白盒单测。
 * <p>两条红线：**库里有的一个都不能删**；**刚写盘、还没入库的也不能删**
 * （上传是「先落盘、后插库」，清理任务正好插在中间时会误删新图）。
 */
class ReceiptStoragePruneTest {

    private Path dir;
    private ReceiptStorage storage;

    @BeforeEach
    void setUp() throws IOException {
        dir = Files.createTempDirectory("bookkeep-orphan-");
        storage = new ReceiptStorage(dir.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 测试收尾，删不掉就算了
                }
            });
        }
    }

    @Test
    @DisplayName("只删「库里没有 + 已经放了一段时间」的文件；新文件与在库文件都保留")
    void onlyRemovesOldOrphans() throws IOException {
        Path kept = write("账本_1/2026-09/keep.png", 120);
        Path orphan = write("账本_1/2026-09/orphan.png", 120);
        Path fresh = write("账本_1/2026-09/fresh.png", 0);   // 刚写好、还没入库

        int removed = storage.pruneOrphans(Set.of("账本_1/2026-09/keep.png"), Duration.ofMinutes(60));

        assertEquals(1, removed);
        assertTrue(Files.exists(kept), "库里有记录的文件必须保留");
        assertFalse(Files.exists(orphan), "老孤儿文件应被删掉");
        assertTrue(Files.exists(fresh), "刚写盘的文件不能删（可能正在上传）");
    }

    @Test
    @DisplayName("库里一条记录都没有时：老文件全清，空目录一并删掉")
    void removesAllWhenNothingInDb() throws IOException {
        write("账本_2/2026-08/a.png", 300);
        write("账本_2/2026-08/b.png", 300);

        assertEquals(2, storage.pruneOrphans(Set.of(), Duration.ofMinutes(60)));
        assertFalse(Files.exists(dir.resolve("账本_2")), "空账本目录也该被清掉");
    }

    @Test
    @DisplayName("目录不存在（从没上传过票据）时返回 0，不抛异常")
    void missingRootIsNoop() throws IOException {
        Files.delete(dir);
        assertEquals(0, storage.pruneOrphans(Set.of(), Duration.ofMinutes(60)));
    }

    /* ---------- 缩略图（派生文件，库里没有它的记录） ---------- */

    @Test
    @DisplayName("原图在库时，它的缩略图也算在库，不能被当孤儿删掉")
    void keepsThumbnailOfKeptReceipt() throws IOException {
        Path original = write("账本_3/2026-09/keep.png", 120);
        Path thumb = write(ReceiptStorage.thumbPath("账本_3/2026-09/keep.png"), 120);

        assertEquals(0, storage.pruneOrphans(Set.of("账本_3/2026-09/keep.png"), Duration.ofMinutes(60)));
        assertTrue(Files.exists(original));
        assertTrue(Files.exists(thumb), "缩略图没有数据库记录，必须靠原图路径推导出来");
    }

    @Test
    @DisplayName("老规格的 _thumb.jpg 是孤儿：规格升级后该被清掉（新缩略图叫 _thumb2.jpg）")
    void dropsThumbnailOfPreviousSpec() throws IOException {
        write("账本_3/2026-09/old.png", 120);
        write("账本_3/2026-09/old_thumb.jpg", 120);
        write(ReceiptStorage.thumbPath("账本_3/2026-09/old.png"), 120);

        assertEquals(1, storage.pruneOrphans(Set.of("账本_3/2026-09/old.png"), Duration.ofMinutes(60)));
        assertFalse(Files.exists(dir.resolve("账本_3/2026-09/old_thumb.jpg")),
                "按长边缩的那批竖条图不该留下来");
        assertTrue(Files.exists(dir.resolve(ReceiptStorage.thumbPath("账本_3/2026-09/old.png"))));
    }

    @Test
    @DisplayName("原图成了孤儿，它的缩略图也一起清掉")
    void removesThumbnailOfOrphan() throws IOException {
        Path thumb = write(ReceiptStorage.thumbPath("账本_4/2026-09/gone.png"), 120);
        write("账本_4/2026-09/gone.png", 120);

        assertEquals(2, storage.pruneOrphans(Set.of(), Duration.ofMinutes(60)));
        assertFalse(Files.exists(thumb));
    }

    @Test
    @DisplayName("删除票据时缩略图当场删掉，不等每日清理")
    void deleteRemovesThumbnailToo() throws IOException {
        Path original = write("账本_5/2026-09/z.jpg", 0);
        Path thumb = write(ReceiptStorage.thumbPath("账本_5/2026-09/z.jpg"), 0);

        storage.deleteQuietly("账本_5/2026-09/z.jpg");

        assertFalse(Files.exists(original));
        assertFalse(Files.exists(thumb));
    }

    @Test
    @DisplayName("缩略图路径规则：同目录、同主名，扩展名固定 jpg")
    void thumbnailPathKeepsDirectory() {
        assertEquals("账本_1/2026-09/abc_thumb2.jpg", ReceiptStorage.thumbPath("账本_1/2026-09/abc.webp"));
        assertEquals("账本_1/2026-09/abc_thumb2.jpg", ReceiptStorage.thumbPath("账本_1/2026-09/abc.png"));
        assertEquals("no_ext_thumb2.jpg", ReceiptStorage.thumbPath("no_ext"));
    }

    /* ---------- 工具 ---------- */

    private Path write(String relative, long ageMinutes) throws IOException {
        Path path = dir.resolve(relative);
        Files.createDirectories(path.getParent());
        try {
            Files.writeString(path, "x");
        } catch (NoSuchFileException e) {
            throw e;
        }
        FileTime stamp = FileTime.from(Instant.now().minus(Duration.ofMinutes(ageMinutes)));
        Files.setLastModifiedTime(path, stamp);
        return path;
    }
}