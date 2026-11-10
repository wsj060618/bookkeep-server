package com.bookkeep.service.export;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.model.entity.Book;
import com.bookkeep.model.entity.Category;
import com.bookkeep.model.entity.Transaction;
import com.bookkeep.model.entity.TransactionReceipt;
import com.bookkeep.service.CurrentBookService;
import com.bookkeep.service.mapper.BookMapper;
import com.bookkeep.service.mapper.CategoryMapper;
import com.bookkeep.service.mapper.TransactionMapper;
import com.bookkeep.service.mapper.TransactionReceiptMapper;
import com.bookkeep.service.support.ReceiptStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * zip 导出（PLAN §10.9），两种包**互相独立**：
 *
 * <pre>
 * ① 账本导出（GET /api/transactions/export?format=zip）——完整备份，可再导入
 *   {账本名}_账本_全部.zip
 *     ├── 流水.csv                     全部流水（与「导出 CSV」同一套内容）
 *     ├── manifest.csv                有票据的流水：日期/类型/分类/金额/备注/标题/相对路径
 *     └── {账本名}_{bookId}/{yyyy-MM}/{票据标题}.{ext}
 *
 * ② 票据导出（GET /api/transactions/receipts/export）——**只要图片**
 *   {账本名}_票据_全部.zip
 *     └── {账本名}_{bookId}/{yyyy-MM}/{票据标题}.{ext}
 *
 * 单笔（GET /api/transactions/{id}/receipts/export）同理只要图片，统一放「图片/」文件夹。
 * </pre>
 *
 * <p>zip 内目录与磁盘一致：解开就能对上 `data/receipts` 的结构，换届打包拷走不用额外整理。
 * <p>上限（≤200 张、≤100MB）在**写响应之前**校验，超了还能正常返回 200+JSON 信封，
 * 而不是写到一半断流。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptExportService {

    /** 一次最多打 200 张：再多就该缩小日期范围，否则 try 机上内存和带宽都扛不住 */
    private static final int MAX_RECEIPTS = 200;

    /** 一次最多 100MB */
    private static final long MAX_BYTES = 100L * 1024 * 1024;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter COMPACT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final CurrentBookService currentBookService;
    private final BookMapper bookMapper;
    private final CategoryMapper categoryMapper;
    private final TransactionMapper transactionMapper;
    private final TransactionReceiptMapper receiptMapper;
    private final ReceiptStorage storage;
    private final TransactionExportService transactionExportService;

    /**
     * 账本导出计划（`format=zip`）：**完整备份包**——流水.csv + manifest.csv + 票据图片。
     * <p>没有票据也允许导出（照样是一份能再导入的流水包），只有「这个范围内没有流水」才 400。
     */
    @Transactional(readOnly = true)
    public ExportPlan prepareBookPackage(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "开始日期不能晚于结束日期");
        }
        Long bookId = currentBookService.currentBookId();
        Book book = bookMapper.selectById(bookId);
        String bookName = book == null ? "账本" : book.getName();
        String safeName = safeFileName(bookName);

        List<Transaction> transactions = selectTransactions(bookId, startDate, endDate);
        if (transactions.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "这个范围内没有流水，没什么可导出的");
        }
        List<TransactionReceipt> receipts = receiptMapper.selectByBookRange(bookId, startDate, endDate);
        guardLimits(receipts);

        // 包里那份流水 CSV：manifest 只记「有票据的流水」，带上它才是「一个包 = 完整备份」
        TransactionExportService.ExportPlan flowPlan = transactionExportService.prepare(startDate, endDate);
        return build(safeName + "_账本_" + rangeSuffix(startDate, endDate) + ".zip",
                transactions, receipts, flowPlan,
                transaction -> safeName + "_" + bookId + "/" + MONTH.format(transaction.getTransDate()) + "/",
                false, true);
    }

    /**
     * 票据导出计划（账本级，可带区间）：**只要图片**，不带 manifest / 流水 CSV。
     * <p>用途是「把票据图拷走」，不是备份；要完整备份用 {@link #prepareBookPackage}。
     * <pre>
     * {账本名}_票据_全部.zip
     *   └── {账本名}_{bookId}/{yyyy-MM}/{票据标题}.{ext}
     * </pre>
     */
    @Transactional(readOnly = true)
    public ExportPlan prepareBookImages(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "开始日期不能晚于结束日期");
        }
        Long bookId = currentBookService.currentBookId();
        Book book = bookMapper.selectById(bookId);
        String bookName = book == null ? "账本" : book.getName();
        String safeName = safeFileName(bookName);

        List<Transaction> transactions = selectTransactions(bookId, startDate, endDate);
        if (transactions.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "这个范围内没有流水，也就没有票据");
        }
        List<TransactionReceipt> receipts = receiptMapper.selectByBookRange(bookId, startDate, endDate);
        if (receipts.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "这个范围内的流水还没有上传票据");
        }
        guardLimits(receipts);

        return build(safeName + "_票据_" + rangeSuffix(startDate, endDate) + ".zip",
                transactions, receipts, null,
                transaction -> safeName + "_" + bookId + "/" + MONTH.format(transaction.getTransDate()) + "/",
                false, false);
    }

    /**
     * 单笔导出计划：这一笔的票据打成 zip，**只要图片**，统一放一个文件夹里。
     * <pre>
     * {账本名}_{日期}_{分类}_{金额}.zip
     *   └── 图片/2026-09-05_超市小票.png
     * </pre>
     */
    @Transactional(readOnly = true)
    public ExportPlan prepareSingle(Long transactionId) {
        Long bookId = currentBookService.currentBookId();
        Transaction transaction = transactionId == null ? null : transactionMapper.selectById(transactionId);
        if (transaction == null || !transaction.getBookId().equals(bookId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "流水不存在");
        }
        List<TransactionReceipt> receipts = receiptMapper.selectByTransaction(transactionId);
        if (receipts.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "这笔流水还没有上传票据");
        }
        guardLimits(receipts);

        Book book = bookMapper.selectById(bookId);
        String bookName = book == null ? "账本" : book.getName();
        Category category = categoryMapper.selectById(transaction.getCategoryId());
        String categoryName = category == null ? "已删除分类" : category.getName();
        String filename = safeFileName(bookName) + "_" + COMPACT.format(transaction.getTransDate())
                + "_" + safeFileName(categoryName) + "_" + transaction.getAmount().toPlainString() + ".zip";
        return build(filename, List.of(transaction), receipts, null, one -> "图片/", true, false);
    }

    /** 流式写 zip：逐个文件 copy，不把图片读进内存；manifest / 流水 CSV 按包里有没有来决定 */
    public void write(ExportPlan plan, OutputStream out) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            if (plan.manifest() != null) {
                zip.putNextEntry(new ZipEntry("manifest.csv"));
                // BOM：manifest 用 Excel 双击打开时中文不乱码（与 CSV 导出同一个约定）
                zip.write("\uFEFF".getBytes(StandardCharsets.UTF_8));
                zip.write(plan.manifest().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }

            // 流水.csv：和「导出 CSV」同一套内容（含标题/汇总/明细），导入端能直接识别
            if (plan.flowPlan() != null) {
                zip.putNextEntry(new ZipEntry("流水.csv"));
                java.io.Writer writer = new java.io.OutputStreamWriter(zip, StandardCharsets.UTF_8);
                transactionExportService.writeCsv(plan.flowPlan(), writer);
                writer.flush();   // 不能 close：那会把整个 zip 关掉
                zip.closeEntry();
            }

            for (Entry entry : plan.entries()) {
                zip.putNextEntry(new ZipEntry(entry.pathInZip()));
                Files.copy(entry.file(), zip);
                zip.closeEntry();
            }
        }
        log.info("导出票据 zip：{}，{} 个文件", plan.filename(), plan.entries().size());
    }

    /* ---------- 内部 ---------- */

    private List<Transaction> selectTransactions(Long bookId, LocalDate startDate, LocalDate endDate) {
        return transactionMapper.selectList(Wrappers.<Transaction>query()
                .eq("book_id", bookId)
                .ge(startDate != null, "trans_date", startDate)
                .le(endDate != null, "trans_date", endDate)
                .orderByAsc("trans_date", "id"));
    }

    /** 文件名里的区间后缀：全部 / 部分_20260901-20260930 */
    private static String rangeSuffix(LocalDate startDate, LocalDate endDate) {
        if (startDate == null && endDate == null) {
            return "全部";
        }
        return "部分_" + (startDate == null ? "起" : COMPACT.format(startDate))
                + "-" + (endDate == null ? "今" : COMPACT.format(endDate));
    }

    private void guardLimits(List<TransactionReceipt> receipts) {
        if (receipts.size() > MAX_RECEIPTS) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "票据太多（" + receipts.size() + " 张），请缩小日期范围后再导出（一次最多 " + MAX_RECEIPTS + " 张）");
        }
        long total = receipts.stream().mapToLong(r -> r.getSizeBytes() == null ? 0 : r.getSizeBytes()).sum();
        if (total > MAX_BYTES) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "票据总大小超过 100MB，请缩小日期范围后再导出");
        }
    }

    private ExportPlan build(String filename,
                             List<Transaction> transactions, List<TransactionReceipt> receipts,
                             TransactionExportService.ExportPlan flowPlan,
                             Function<Transaction, String> dirOf, boolean dateInName,
                             boolean withManifest) {
        Map<Long, Transaction> byId = transactions.stream()
                .collect(Collectors.toMap(Transaction::getId, Function.identity()));
        Map<Long, String> categoryNames = loadCategoryNames(transactions);

        List<Entry> entries = new ArrayList<>(receipts.size());
        // 只要图片的包（票据导出/单笔导出）不生成 manifest：那不是备份包，是「把图拷走」
        StringBuilder manifest = withManifest
                ? new StringBuilder("日期,类型,分类,金额,备注,票据标题,相对路径\n")
                : null;
        // 同一目录下重名就加 _2 / _3（两张「超市小票」很常见）
        Map<String, Integer> seen = new HashMap<>();

        for (TransactionReceipt receipt : receipts) {
            Transaction transaction = byId.get(receipt.getTransactionId());
            if (transaction == null) {
                continue;
            }
            String dir = dirOf.apply(transaction);
            String ext = extOf(receipt.getContentType());
            // 账本级：目录已经是「账本/年月」，文件名直接用票据标题，不再重复日期；
            // 单笔：图片都在一个「图片/」文件夹里，没有日期信息，文件名保留日期前缀。
            String base = dateInName
                    ? DATE.format(transaction.getTransDate()) + "_" + safeFileName(receipt.getTitle())
                    : safeFileName(receipt.getTitle());
            String key = dir + base;
            int seq = seen.merge(key, 1, Integer::sum);
            String name = seq == 1 ? base + "." + ext : base + "_" + seq + "." + ext;
            String pathInZip = dir + name;

            Path file = storagePath(receipt.getStoredPath());
            // 磁盘上没这张图（只恢复了数据库、或手工删过文件）时不能直接拷贝：
            // Files.copy 会抛异常，而响应早已开始写，客户端只会拿到一个「图是 0 字节」的坏包。
            // 这里跳过文件本身，但 manifest 里留一行「文件缺失」，便于人工核对缺了什么。
            boolean readable = Files.isReadable(file);
            if (readable) {
                entries.add(new Entry(pathInZip, file));
            } else {
                log.warn("导出票据时文件缺失，已跳过：receiptId={}, path={}", receipt.getId(), file);
            }

            if (manifest != null) {
                manifest.append(DATE.format(transaction.getTransDate())).append(',')
                        .append("income".equals(transaction.getType()) ? "收入" : "支出").append(',')
                        .append(csv(categoryNames.getOrDefault(transaction.getCategoryId(), "已删除分类"))).append(',')
                        .append(transaction.getAmount().toPlainString()).append(',')
                        .append(csv(transaction.getNote() == null ? "" : transaction.getNote())).append(',')
                        .append(csv(receipt.getTitle())).append(',')
                        .append(csv(readable ? pathInZip : pathInZip + "（文件缺失，未打包）")).append('\n');
            }
        }
        return new ExportPlan(filename, entries, manifest == null ? null : manifest.toString(), flowPlan);
    }

    private Path storagePath(String storedPath) {
        return storage.resolve(storedPath);
    }

    private Map<Long, String> loadCategoryNames(List<Transaction> transactions) {
        List<Long> ids = transactions.stream().map(Transaction::getCategoryId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return categoryMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
    }

    private static String extOf(String contentType) {
        return switch (contentType == null ? "" : contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
    }

    private static String csv(String value) {
        String text = value == null ? "" : value;
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    /** 文件名/目录名里不能出现的字符（跨 Windows/Linux 都安全的那一set） */
    private static String safeFileName(String name) {
        String cleaned = name == null ? "" : name.trim()
                .replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_")
                .replaceAll("^\\.+", "")
                .trim();
        if (cleaned.isEmpty()) {
            return "账本";
        }
        return cleaned.length() > 30 ? cleaned.substring(0, 30) : cleaned;
    }

    /** zip 内一个文件；写出时只按路径 copy，不缓存字节 */
    public record Entry(String pathInZip, Path file) {
    }

    /** 导出计划：文件名 + 条目 + manifest 内容（仅账本包有）+ 随包附带的流水 CSV 计划（仅账本包有） */
    public record ExportPlan(String filename, List<Entry> entries, String manifest,
                             TransactionExportService.ExportPlan flowPlan) {
    }
}