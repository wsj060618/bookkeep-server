package com.bookkeep.service.support;

import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 票据文件落盘规则：{receipt.dir}/{安全化账本名}_{bookId}/{yyyy-MM}/{uuid}.{ext}。
 * <p>文件名用 UUID，用户标题不进路径；扩展名按魔数类型定；改名不搬目录（stored_path 是唯一事实来源）。
 */
@Slf4j
@Component
public class ReceiptStorage {

    /** 单张 10MB */
    public static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    /** 单笔最多 9 张 */
    public static final int MAX_PER_TRANSACTION = 9;

    /** 单账号 500MB */
    public static final long MAX_BYTES_PER_USER = 500L * 1024 * 1024;

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    /** Windows 不允许出现在目录名里的字符 */
    private static final String ILLEGAL = "\\\\/:*?\"<>|";

    private static final int DIR_NAME_MAX = 30;

    private final Path root;

    public ReceiptStorage(@Value("${app.receipt.dir:./data/receipts}") String dir) {
        this.root = Paths.get(dir).toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /* ---------- 类型识别 ---------- */

    /** 按魔数识别图片类型；不认识就抛 400（SVG 属于「不认识」，它能带脚本，等于存储型 XSS） */
    public static ImageType detect(byte[] bytes) {
        if (bytes == null || bytes.length < 12) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件太小，不像是图片");
        }
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return ImageType.JPEG;
        }
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && (bytes[4] & 0xFF) == 0x0D && (bytes[5] & 0xFF) == 0x0A
                && (bytes[6] & 0xFF) == 0x1A && (bytes[7] & 0xFF) == 0x0A) {
            return ImageType.PNG;
        }
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return ImageType.WEBP;
        }
        throw new BusinessException(ResultCode.BAD_REQUEST, "只支持 jpg / png / webp 图片");
    }

    /** 图片类型：扩展名与 Content-Type 都由它决定，不让调用方各写一份 */
    public enum ImageType {
        JPEG("jpg", "image/jpeg"),
        PNG("png", "image/png"),
        WEBP("webp", "image/webp");

        private final String ext;
        private final String contentType;

        ImageType(String ext, String contentType) {
            this.ext = ext;
            this.contentType = contentType;
        }

        public String ext() {
            return ext;
        }

        public String contentType() {
            return contentType;
        }
    }

    /* ---------- 路径规则 ---------- */

    /**
     * 账本目录名：安全化后的账本名 + {@code _bookId}。
     * <p>拼 bookId 是为了改名/重名时不串目录；账本名全是非法字符时回落 {@code book_{id}}。
     */
    public static String bookDirName(Long bookId, String bookName) {
        String cleaned = bookName == null ? "" : bookName;
        StringBuilder sb = new StringBuilder(cleaned.length());
        for (char c : cleaned.toCharArray()) {
            if (ILLEGAL.indexOf(c) >= 0 || c < 0x20) {
                continue;
            }
            sb.append(c);
        }
        String safe = sb.toString().trim().replaceAll("^\\.+", "").replaceAll("\\.+$", "").trim();
        if (safe.length() > DIR_NAME_MAX) {
            safe = safe.substring(0, DIR_NAME_MAX).trim();
        }
        if (safe.isEmpty()) {
            safe = "book";
        }
        return safe + "_" + bookId;
    }

    /** 相对路径：{账本目录}/{yyyy-MM}/{uuid}.{ext} */
    public static String relativePath(String bookDir, LocalDate transDate, String ext) {
        return bookDir + "/" + MONTH.format(transDate) + "/" + UUID.randomUUID().toString().replace("-", "")
                + "." + ext;
    }

    /**
     * 缩略图相对路径：与原始文件同目录，文件名后缀 {@code _thumb2.jpg}。
     * <p>永远是 JPEG（原图可能是 png/webp），所以扩展名固定。库里不存这条路径——它是派生物，
     * 由原始文件名推导，「原图在库 → 缩略图也算在库」。
     * <p><b>2 这个后缀是规格版本号</b>：2026-11-12 之前按「长边 480」缩放，
     * 手机长截图会被缩成 29px 宽的竖条；改成按宽度缩之后换文件名，
     * 老缩略图自然失效、按需重生成，旧的孤儿文件由每日清理带走。
     */
    public static String thumbPath(String storedPath) {
        int slash = storedPath.lastIndexOf('/');
        int dot = storedPath.lastIndexOf('.');
        int cut = dot > slash ? dot : storedPath.length();
        return storedPath.substring(0, cut) + "_thumb2.jpg";
    }

    /** 缩略图绝对路径 */
    public Path thumbOf(String storedPath) {
        return resolve(thumbPath(storedPath));
    }

    /** 把库里的相对路径解析成本机绝对路径，并挡住越界（理论上不会发生，但这是最后一道闸） */
    public Path resolve(String storedPath) {
        Path path = root.resolve(storedPath).normalize();
        if (!path.startsWith(root)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "票据不存在");
        }
        return path;
    }

    /* ---------- 读写 ---------- */

    public void write(String storedPath, byte[] bytes) {
        Path path = resolve(storedPath);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, bytes);
        } catch (IOException e) {
            log.error("票据落盘失败：{}", path, e);
            throw new BusinessException(ResultCode.ERROR, "票据保存失败，请稍后重试");
        }
    }

    /** 删除单个文件：失败只记日志，不把业务流程带崩（文件删不掉比上传失败影响小得多） */
    public void deleteQuietly(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(storedPath));
            // 缩略图是派生物，跟着原图一起走，否则会留成每天清理任务才发现的孤儿
            Files.deleteIfExists(thumbOf(storedPath));
        } catch (RuntimeException | IOException e) {
            log.warn("删除票据文件失败（忽略）：{}，原因：{}", storedPath, e.getMessage());
        }
    }

    /**
     * 写缩略图：失败只记日志——缩略图是优化，不能让它把上传或预览带崩。
     * <p>先写临时文件再原子重命名：并发生成同一张时，读者要么看不到、要么看到完整的那份。
     */
    public void writeThumbQuietly(String storedPath, byte[] bytes) {
        Path target = thumbOf(storedPath);
        Path tmp = null;
        try {
            Files.createDirectories(target.getParent());
            tmp = Files.createTempFile(target.getParent(), "thumb", ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            tmp = null;
        } catch (IOException | RuntimeException e) {
            log.warn("缩略图写入失败（忽略）：{}，原因：{}", target, e.getMessage());
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // 清理失败无所谓，临时文件也是孤儿清理的活儿
                }
            }
        }
    }

    /** 删掉整本账本的票据目录；失败只记日志（注销账号不能因为文件删不掉就回滚） */
    public void deleteBookDirQuietly(Long bookId, String bookName) {
        try {
            deleteRecursively(root.resolve(bookDirName(bookId, bookName)).normalize());
        } catch (RuntimeException e) {
            log.warn("删除账本票据目录失败（忽略）：bookId={}，原因：{}", bookId, e.getMessage());
        }
    }

    private void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            log.warn("删除目录失败（忽略）：{}，原因：{}", dir, e.getMessage());
        }
    }

    /**
     * 清理孤儿文件：磁盘上有、库里没有对应记录的图片。
     * <p>哪些情况会产生孤儿：上传时先写盘再插库，插库失败/回滚；手工删库；迁移时只拷了一半。
     * <p><b>{@code minAge} 不能省</b>：正在上传的文件是「先落盘、后插库」，如果清理任务正好插在两者之间，
     * 会把一张刚写好、还没入库的图删掉。所以只清理「最后修改时间早于 minAge」的文件。
     *
     * @param keepPaths 库里仍然有效的相对路径（正斜杠）
     * @return 实际删除的文件数
     */
    public int pruneOrphans(Set<String> keepPaths, java.time.Duration minAge) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        // 缩略图在库里没有记录：原图在库，它的 _thumb.jpg 就算在库（否则每天 4:20 全部缩略图被清空）
        Set<String> keep = new HashSet<>(keepPaths);
        for (String storedPath : keepPaths) {
            keep.add(thumbPath(storedPath));
        }
        long cutoff = System.currentTimeMillis() - minAge.toMillis();
        int removed = 0;
        try (var walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (keep.contains(relative)) {
                    continue;
                }
                try {
                    if (Files.getLastModifiedTime(path).toMillis() > cutoff) {
                        continue; // 可能是刚上传、还没入库的
                    }
                    Files.delete(path);
                    removed++;
                } catch (IOException e) {
                    log.warn("删除孤儿票据失败（忽略）：{}，原因：{}", relative, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("扫描票据目录失败：{}，原因：{}", root, e.getMessage());
            return removed;
        }
        removeEmptyDirs(root);
        return removed;
    }

    /** 顺手删掉空目录（账本删完、文件清完会剩下空壳） */
    private void removeEmptyDirs(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .filter(Files::isDirectory)
                    .filter(path -> !path.equals(root))
                    .forEach(path -> {
                        try (var children = Files.list(path)) {
                            if (children.findAny().isEmpty()) {
                                Files.delete(path);
                            }
                        } catch (IOException e) {
                            log.debug("删除空目录失败（忽略）：{}", path);
                        }
                    });
        } catch (IOException e) {
            log.debug("清理空目录失败（忽略）：{}", e.getMessage());
        }
    }

    /** 原始文件名去扩展名当默认标题（「超市小票.jpg」→「超市小票」），空/超长时兜底 */
    public static String defaultTitle(String originalName) {
        String name = originalName == null ? "" : originalName.trim();
        if (name.startsWith(".")) {
            // ".jpg" / ".hidden" 这类只有扩展名/隐藏名的文件，没有可用的标题
            name = "";
        } else {
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                name = name.substring(0, dot);
            }
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", " ").trim();
        if (name.isEmpty()) {
            return "票据";
        }
        return name.length() > 30 ? name.substring(0, 30) : name;
    }

    public static String lowerExt(String filename) {
        String name = filename == null ? "" : filename;
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}