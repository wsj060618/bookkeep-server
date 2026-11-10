package com.bookkeep.service.support;

import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 用户上传的 zip 解包，按恶意输入处理：
 * 条目 ≤1000、单条目 ≤20MB、总量 ≤100MB（边读边计数）、路径必须在临时目录内。
 * <p>macOS 打包残留（__MACOSX/、.DS_Store）跳过。
 */
@Slf4j
public final class ZipExtractor {

    public static final int MAX_ENTRIES = 1000;
    public static final long MAX_TOTAL_BYTES = 100L * 1024 * 1024;
    private static final long MAX_ENTRY_BYTES = 20L * 1024 * 1024;

    private ZipExtractor() {
    }

    /** 解到临时目录，返回目录路径；调用方负责删除 */
    public static Path extract(byte[] zipBytes) {
        if (zipBytes == null || zipBytes.length == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "压缩包是空的");
        }
        Path dir;
        try {
            dir = Files.createTempDirectory("bookkeep-import-");
        } catch (IOException e) {
            throw new BusinessException(ResultCode.ERROR, "无法创建临时目录");
        }

        int entries = 0;
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new BusinessException(ResultCode.BAD_REQUEST,
                            "压缩包里文件太多（上限 " + MAX_ENTRIES + " 个）");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                String name = sanitize(entry.getName());
                if (name == null) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "压缩包里有非法路径：" + entry.getName());
                }
                if (isJunk(name)) {
                    continue;
                }
                byte[] data = readBounded(in);
                total += data.length;
                if (total > MAX_TOTAL_BYTES) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "压缩包解压后超过 100MB，请分批导入");
                }
                Path out = dir.resolve(name).normalize();
                if (!out.startsWith(dir)) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "压缩包里有非法路径：" + entry.getName());
                }
                Files.createDirectories(out.getParent());
                Files.write(out, data);
            }
        } catch (IOException e) {
            deleteQuietly(dir);
            throw new BusinessException(ResultCode.BAD_REQUEST, "压缩包损坏或不是 zip 文件：" + e.getMessage());
        } catch (RuntimeException e) {
            deleteQuietly(dir);
            throw e;
        }
        return dir;
    }

    /** 目录下所有文件（相对路径用 `/`，排序稳定便于报错定位） */
    public static List<Path> files(Path dir) {
        try (var walk = Files.walk(dir)) {
            List<Path> files = new ArrayList<>();
            walk.filter(Files::isRegularFile).forEach(files::add);
            files.sort(Comparator.comparing(path -> dir.relativize(path).toString()));
            return files;
        } catch (IOException e) {
            throw new BusinessException(ResultCode.ERROR, "读取压缩包内容失败");
        }
    }

    public static String relative(Path dir, Path file) {
        return dir.relativize(file).toString().replace('\\', '/');
    }

    public static void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.debug("清理临时文件失败（忽略）：{}", path);
                }
            });
        } catch (IOException e) {
            log.warn("清理临时目录失败：{}，原因：{}", dir, e.getMessage());
        }
    }

    /* ---------- 内部 ---------- */

    /** 边读边计数：超了直接抛，不能先 readAllBytes 再判断（zip 炸弹就是靠这一步防的） */
    private static byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long size = 0;
        int read;
        while ((read = in.read(buffer)) > 0) {
            size += read;
            if (size > MAX_ENTRY_BYTES) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "压缩包里有单个文件超过 20MB，请检查");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * 规范化条目名，非法则返回 {@code null}：
     * 去掉前导 `./`、把 `\` 统一成 `/`；含 `..` 段、以 `/` 开头、带盘符的都算非法。
     */
    static String sanitize(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return null;
        }
        String name = rawName.replace('\\', '/').trim();
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        if (name.startsWith("/") || name.matches("^[A-Za-z]:/.*")) {
            return null;
        }
        for (String part : name.split("/")) {
            if ("..".equals(part)) {
                return null;
            }
        }
        return name;
    }

    private static boolean isJunk(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        String base = lower.substring(lower.lastIndexOf('/') + 1);
        return lower.startsWith("__macosx/") || base.equals(".ds_store") || base.startsWith("._");
    }
}