package com.bookkeep.service.imports;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.model.dto.TemplateDoc;
import com.bookkeep.model.dto.resp.ImportResultResp;
import com.bookkeep.model.dto.resp.ReceiptImportResp;
import com.bookkeep.model.dto.resp.ZipImportResp;
import com.bookkeep.model.entity.Category;
import com.bookkeep.model.entity.Transaction;
import com.bookkeep.model.validation.MoneyRules;
import com.bookkeep.service.CurrentBookService;
import com.bookkeep.service.ReceiptService;
import com.bookkeep.service.constant.CategoryPresets;
import com.bookkeep.service.mapper.CategoryMapper;
import com.bookkeep.service.mapper.TransactionMapper;
import com.bookkeep.service.support.CsvReader;
import com.bookkeep.service.support.TemplateDocs;
import com.bookkeep.service.support.ZipExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 账本包导入：流水.csv + manifest.csv + 图片。
 * <p>必须先导 CSV 再挂图，否则只有支出票据的包会因余额不足失败；
 * 按「日期+类型+分类+金额+备注」匹配流水。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptZipImportService {

    private static final String MANIFEST = "manifest.csv";

    /** 账本包里记录分类图标/颜色的 JSON（没有也行，退化成默认样式） */
    private static final String TEMPLATE_JSON = "分类模板.json";

    private final CurrentBookService currentBookService;
    private final TransactionMapper transactionMapper;
    private final CategoryMapper categoryMapper;
    private final TransactionImportService transactionImportService;
    private final ReceiptService receiptService;
    private final ObjectMapper objectMapper;

    /** manifest 的一行（已展开成可直接建流水的字段） */
    private record ManifestRow(int line, String date, String type, String category,
                               String amount, String note, String title, String relativePath) {
    }

    @Transactional(rollbackFor = Exception.class)
    public ZipImportResp importZip(String filename, byte[] content, boolean createMissing) {
        if (content == null || content.length == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "压缩包是空的，请检查后重传");
        }
        Long bookId = currentBookService.writableBookId();
        Path dir = ZipExtractor.extract(content);
        try {
            List<Path> files = ZipExtractor.files(dir);
            Path manifest = find(files, dir, MANIFEST);
            if (manifest == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "包内缺少 manifest.csv：请用本系统「流水 → 导出 → 账本导出（zip）」生成的压缩包");
            }
            Path flowCsv = findFlowCsv(files, dir);
            List<ManifestRow> manifestRows = parseManifest(manifest);
            List<TransactionImportService.RawRow> flowRows = readFlowRows(dir, flowCsv);
            Map<String, TemplateDoc.Item> props = readTemplateProps(files, dir);

            Map<String, Long> categoryIds = loadCategoryIds(bookId);

            // 分类预检要覆盖「流水.csv」和「manifest.csv」两边：只在 manifest 里出现的流水也会建分类
            List<TransactionImportService.RawRow> allRows = new ArrayList<>(flowRows);
            for (ManifestRow row : manifestRows) {
                allRows.add(new TransactionImportService.RawRow(row.line(), row.date(), row.type(),
                        row.category(), row.amount(), row.note()));
            }
            List<ImportResultResp.MissingCategory> missing =
                    transactionImportService.missingCategories(allRows, categoryIds);
            if (!missing.isEmpty()) {
                // 缺分类时**整包都不写**：让前端先问「要不要新建」，否则会导一半、补的那半又重复
                if (!createMissing) {
                    return new ZipImportResp(0, List.of(), new ReceiptImportResp(0, List.of()), missing);
                }
                transactionImportService.createCategories(missing, categoryIds, bookId, props);
            }

            int success = 0;
            List<ImportResultResp.FailedRow> failed = new ArrayList<>();
            // 同一个键在 流水.csv 和 manifest.csv 里各出现一次：失败只报一遍，别让人以为丢了两笔
            Set<String> failedKeys = new HashSet<>();
            // 键 → 还没挂满 9 张的同键流水，按导入顺序排队
            Map<String, Deque<Transaction>> candidates = new HashMap<>();

            // 1) 先导流水（CSV 里有全部流水，余额顺序也正确）
            for (var row : flowRows) {
                try {
                    Transaction transaction = transactionImportService.toTransaction(
                            row, bookId, categoryIds);
                    transactionMapper.insert(transaction);
                    success++;
                    candidates.computeIfAbsent(keyOf(transaction), k -> new ArrayDeque<>()).add(transaction);
                } catch (BusinessException e) {
                    failed.add(new ImportResultResp.FailedRow(row.line(), e.getMessage()));
                    failedKeys.add(rawKey(row.date(), row.type(), row.category(), row.amount(), row.note()));
                }
            }

            // 2) 按 manifest 挂图；manifest 里有而 CSV 里没有的，在这里补建流水
            int receiptsOk = 0;
            List<ReceiptImportResp.FailedFile> receiptFailed = new ArrayList<>();
            Map<Long, Integer> used = new HashMap<>();
            for (ManifestRow row : manifestRows) {
                // 这笔流水在上面已经因为同样原因失败过（比如分类不存在）：不再重复报一次
                if (failedKeys.contains(rawKey(row.date(), row.type(), row.category(), row.amount(), row.note()))) {
                    continue;
                }
                Transaction target;
                try {
                    target = pickTarget(row, categoryIds, candidates);
                    if (target == null) {
                        target = transactionImportService.toTransaction(
                                new TransactionImportService.RawRow(row.line(), row.date(), row.type(),
                                        row.category(), row.amount(), row.note()),
                                bookId, categoryIds);
                        transactionMapper.insert(target);
                        success++;
                        candidates.computeIfAbsent(keyOf(target), k -> new ArrayDeque<>()).add(target);
                    }
                } catch (BusinessException e) {
                    failed.add(new ImportResultResp.FailedRow(row.line(), e.getMessage()));
                    continue;
                }

                Path image = dir.resolve(row.relativePath()).normalize();
                if (!image.startsWith(dir) || !Files.isRegularFile(image)) {
                    receiptFailed.add(new ReceiptImportResp.FailedFile(row.relativePath(), "包内找不到这张图片"));
                    continue;
                }
                try {
                    receiptService.upload(target.getId(), image.getFileName().toString(),
                            Files.readAllBytes(image), row.title());
                    receiptsOk++;
                    int count = used.merge(target.getId(), 1, Integer::sum);
                    if (count >= 9) {
                        // 这一笔挂满了，同键的下一条流水顶上（完全相同的一笔可能有多个）
                        Deque<Transaction> queue = candidates.get(keyOf(target));
                        if (queue != null) {
                            queue.remove(target);
                        }
                    }
                } catch (BusinessException e) {
                    receiptFailed.add(new ReceiptImportResp.FailedFile(row.relativePath(), e.getMessage()));
                } catch (IOException e) {
                    receiptFailed.add(new ReceiptImportResp.FailedFile(row.relativePath(), "读取图片失败"));
                }
            }

            log.info("导入账本包：bookId={}, 文件={}, 流水 {} 笔，票据 {} 张，失败流水 {} / 失败票据 {}",
                    bookId, filename, success, receiptsOk, failed.size(), receiptFailed.size());
            return new ZipImportResp(success, failed, new ReceiptImportResp(receiptsOk, receiptFailed));
        } finally {
            ZipExtractor.deleteQuietly(dir);
        }
    }

    /* ---------- 内部 ---------- */

    /** 读包内的「流水.csv」；没有就返回空列表（兼容只有票据的包） */
    private List<TransactionImportService.RawRow> readFlowRows(Path dir, Path flowCsv) {
        if (flowCsv == null) {
            return List.of();
        }
        byte[] flowBytes;
        try {
            flowBytes = Files.readAllBytes(flowCsv);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "读不到包内的流水 CSV");
        }
        return transactionImportService.parse(ZipExtractor.relative(dir, flowCsv), flowBytes);
    }

    /**
     * 包里那份「分类模板.json」：只用来给新建分类补图标/颜色。
     * <p>缺失或解析失败都只记一条日志 —— 它决定不了导入成败，包能导进来才是正事。
     */
    private Map<String, TemplateDoc.Item> readTemplateProps(List<Path> files, Path dir) {
        Path json = find(files, dir, TEMPLATE_JSON);
        if (json == null) {
            return Map.of();
        }
        try {
            TemplateDoc doc = objectMapper.readValue(Files.readAllBytes(json), TemplateDoc.class);
            return TemplateDocs.index(doc.items());
        } catch (IOException e) {
            log.warn("包内的 {} 解析失败，分类样式用默认值：{}", TEMPLATE_JSON, e.getMessage());
            return Map.of();
        }
    }

    /** 找与 manifest 同键、且还没挂满的流水；没有就返回 null（由调用方补建） */
    private Transaction pickTarget(ManifestRow row, Map<String, Long> categoryIds,
                                   Map<String, Deque<Transaction>> candidates) {
        Long categoryId = categoryIds.get(normalizeType(row.type()) + "|" + row.category().trim());
        if (categoryId == null) {
            return null; // 让 buildTransaction 去抛「分类不存在」的清晰错误
        }
        BigDecimal amount = parseAmount(row.amount());
        String key = keyOf(LocalDate.parse(row.date().trim()), normalizeType(row.type()),
                categoryId, amount, row.note());
        Deque<Transaction> queue = candidates.get(key);
        return queue == null || queue.isEmpty() ? null : queue.peek();
    }

    private static String keyOf(Transaction transaction) {
        return keyOf(transaction.getTransDate(), transaction.getType(), transaction.getCategoryId(),
                transaction.getAmount(), transaction.getNote());
    }

    /** 失败去重用的键：用**原始字符串**拼，金额都解析不出来时也能匹配上 */
    private static String rawKey(String date, String type, String category, String amount, String note) {
        return String.join("|", nz(date), nz(type), nz(category), nz(amount), nz(note));
    }

    private static String nz(String value) {
        return value == null ? "" : value.trim();
    }

    private static String keyOf(LocalDate date, String type, Long categoryId, BigDecimal amount, String note) {
        return date + "|" + type + "|" + categoryId + "|" + amount.toPlainString() + "|"
                + (note == null ? "" : note.trim());
    }

    /** manifest.csv：日期,类型,分类,金额,备注,票据标题,相对路径 —— 一行 = 一张票据 */
    private List<ManifestRow> parseManifest(Path manifest) {
        String text;
        try {
            text = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "读不到 manifest.csv");
        }
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        List<ManifestRow> rows = new ArrayList<>();
        boolean headerFound = false;
        for (CsvReader.Row row : CsvReader.parse(text)) {
            if (!headerFound) {
                if ("日期".equals(row.at(0).trim())) {
                    headerFound = true;
                }
                continue;
            }
            if (row.cells().size() < 7) {
                // 列数不够说明这一行本身坏了：补一条失败记录，别让人以为图丢了
                log.warn("manifest.csv 第 {} 行列数不足（{} 列），已跳过", row.line(), row.cells().size());
                continue;
            }
            rows.add(new ManifestRow(row.line(), row.at(0), row.at(1), row.at(2),
                    row.at(3), row.at(4), row.at(5), row.at(6)));
        }
        if (!headerFound) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "manifest.csv 的表头不对：应为「日期,类型,分类,金额,备注,票据标题,相对路径」");
        }
        return rows;
    }

    private static String normalizeType(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "income", "收入" -> CategoryPresets.TYPE_INCOME;
            case "expense", "支出" -> CategoryPresets.TYPE_EXPENSE;
            default -> throw new BusinessException(ResultCode.BAD_REQUEST,
                    "类型只能是 收入/支出（或 income/expense），实际：" + raw);
        };
    }

    private static BigDecimal parseAmount(String raw) {
        BigDecimal amount;
        try {
            amount = new BigDecimal(raw == null ? "" : raw.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "金额不是有效数字：" + raw);
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "金额必须大于 0");
        }
        if (amount.compareTo(MoneyRules.MAX) > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, MoneyRules.RANGE_MESSAGE);
        }
        return amount.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private Map<String, Long> loadCategoryIds(Long bookId) {
        Map<String, Long> map = new HashMap<>();
        for (Category category : categoryMapper.selectList(Wrappers.<Category>query().eq("book_id", bookId))) {
            map.put(category.getType() + "|" + category.getName(), category.getId());
        }
        return map;
    }

    private static Path find(List<Path> files, Path dir, String name) {
        for (Path file : files) {
            if (name.equalsIgnoreCase(ZipExtractor.relative(dir, file))) {
                return file;
            }
        }
        // 允许放在一级子目录里（用户重新打包时常见）
        for (Path file : files) {
            String relative = ZipExtractor.relative(dir, file);
            if (relative.toLowerCase(Locale.ROOT).endsWith("/" + name)) {
                return file;
            }
        }
        return null;
    }

    /** 流水 CSV：优先「流水.csv」，否则任意一个不是 manifest 的 csv */
    private static Path findFlowCsv(List<Path> files, Path dir) {
        Path fallback = null;
        for (Path file : files) {
            String relative = ZipExtractor.relative(dir, file);
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".csv") || name.equals(MANIFEST)) {
                continue;
            }
            if (name.equals("流水.csv")) {
                return file;
            }
            if (fallback == null) {
                fallback = file;
            }
        }
        return fallback;
    }
}