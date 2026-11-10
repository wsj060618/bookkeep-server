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