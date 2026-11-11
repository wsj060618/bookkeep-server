package com.bookkeep.service.imports;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.model.dto.TemplateDoc;
import com.bookkeep.model.dto.resp.ImportResultResp;
import com.bookkeep.model.dto.req.CategoryReq;
import com.bookkeep.model.entity.Category;
import com.bookkeep.model.entity.Transaction;
import com.bookkeep.model.validation.MoneyRules;
import com.bookkeep.service.CategoryService;
import com.bookkeep.service.CurrentBookService;
import com.bookkeep.service.constant.CategoryPresets;
import com.bookkeep.service.mapper.CategoryMapper;
import com.bookkeep.service.mapper.TransactionMapper;
import com.bookkeep.service.support.CsvReader;
import com.bookkeep.service.support.TemplateDocs;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 流水导入（CSV，列固定为 日期,类型,分类,金额,备注）。
 * <p>行级失败不阻塞其它行；文件里用到账本没有的分类时先停下等用户确认，不悄悄建也不导一半。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionImportService {

    /** 一次最多 2000 行 */
    private static final int MAX_ROWS = 2000;

    /** 与 application.yml 的 multipart 限制一致 */
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private static final int NOTE_MAX = 255;

    private final CurrentBookService currentBookService;
    private final TransactionMapper transactionMapper;
    private final CategoryMapper categoryMapper;
    private final CategoryService categoryService;

    /** 文件里的一行（已解析、未校验）；账本包导入也复用它 */
    record RawRow(int line, String date, String type, String category, String amount, String note) {
    }

    /**
     * @param filename      原始文件名（用来判断 csv / xlsx）
     * @param content       文件字节
     * @param createMissing 文件里用到、账本里还没有的分类要不要顺手建掉（默认不建）
     */
    @Transactional(rollbackFor = Exception.class)
    public ImportResultResp importFile(String filename, byte[] content, boolean createMissing) {
        Long bookId = currentBookService.writableBookId();
        List<RawRow> rows = parseChecked(filename, content);

        Map<String, Long> categoryIds = loadCategoryIds(bookId);
        List<ImportResultResp.MissingCategory> missing = missingCategories(rows, categoryIds);
        if (!missing.isEmpty()) {
            // 有缺的分类时**一行都不写**：让前端先问「要不要新建」，避免导一半再补一半（补的那半会重复）
            if (!createMissing) {
                return new ImportResultResp(0, List.of(), missing);
            }
            createCategories(missing, categoryIds, bookId);
        }

        int success = 0;
        List<ImportResultResp.FailedRow> failed = new ArrayList<>();
        for (RawRow row : rows) {
            try {
                Transaction transaction = toTransaction(row, bookId, categoryIds);
                transactionMapper.insert(transaction);
                success++;
            } catch (BusinessException e) {
                failed.add(new ImportResultResp.FailedRow(row.line(), e.getMessage()));
            }
        }

        log.info("导入流水：bookId={}, 文件={}, 成功 {} 行，失败 {} 行，新建分类 {} 个",
                bookId, filename, success, failed.size(), missing.size());
        return new ImportResultResp(success, failed);
    }

    /* ---------- 分类预检 ---------- */

    /** 文件里用到、账本还没有的分类（保持首次出现顺序，同一个不重复） */
    List<ImportResultResp.MissingCategory> missingCategories(List<RawRow> rows, Map<String, Long> categoryIds) {
        Map<String, ImportResultResp.MissingCategory> missing = new LinkedHashMap<>();
        for (RawRow row : rows) {
            String name = row.category() == null ? "" : row.category().trim();
            if (name.isEmpty()) {
                continue;
            }
            String type;
            try {
                type = normalizeType(row.type());
            } catch (BusinessException e) {
                continue; // 类型写错的行会在正式导入时报错，这里不算「缺分类」
            }
            if (categoryIds.containsKey(type + "|" + name)) {
                continue;
            }
            missing.putIfAbsent(type + "|" + name, new ImportResultResp.MissingCategory(name, type));
        }
        return new ArrayList<>(missing.values());
    }

    /** 走 CategoryService 建：和「分类」页新建一样，会同步补进这个账本用的分类模板 */
    void createCategories(List<ImportResultResp.MissingCategory> missing, Map<String, Long> categoryIds,
                          Long bookId) {
        createCategories(missing, categoryIds, bookId, Map.of());
    }

    /**
     * @param props 来自账本包里的 {@code 分类模板.json}：拿得到就用它的图标/颜色新建，
     *              否则退化成默认的 other / gray（CSV 导入没有这份文件）
     */
    void createCategories(List<ImportResultResp.MissingCategory> missing, Map<String, Long> categoryIds,
                          Long bookId, Map<String, TemplateDoc.Item> props) {
        for (ImportResultResp.MissingCategory item : missing) {
            TemplateDoc.Item prop = props.get(TemplateDocs.key(item.type(), item.name()));
            categoryService.create(new CategoryReq(item.name(), item.type(),
                    prop == null ? null : prop.icon(), prop == null ? null : prop.color()));
            log.info("导入时新建分类：{}（{}）", item.name(), item.type());
        }
        // 重建映射：新建的分类要能被后面的行认出来
        categoryIds.clear();
        categoryIds.putAll(loadCategoryIds(bookId));
    }

    private List<RawRow> parseChecked(String filename, byte[] content) {
        if (content == null || content.length == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件是空的，请检查后重传");
        }
        if (content.length > MAX_BYTES) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件不能超过 5MB");
        }
        // 归档账本只读：先拦，别解析完才发现不能写
        currentBookService.writableBookId();

        List<RawRow> rows = parse(filename, content);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件里没有可导入的数据行");
        }
        if (rows.size() > MAX_ROWS) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "一次最多导入 " + MAX_ROWS + " 行，请拆成多个文件");
        }
        return rows;
    }

    /* ---------- 校验 + 落库 ---------- */

    /** 包级可见：账本包（zip）导入按 manifest 补建流水时复用同一套校验 */
    Transaction toTransaction(RawRow row, Long bookId, Map<String, Long> categoryIds) {
        LocalDate transDate;
        try {
            transDate = LocalDate.parse(row.date().trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "日期格式应为 yyyy-MM-dd（实际：" + row.date() + "）");
        }

        String type = normalizeType(row.type());
        String categoryName = row.category().trim();
        Long categoryId = categoryIds.get(type + "|" + categoryName);
        if (categoryId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "分类「" + categoryName + "」不存在（" + ("income".equals(type) ? "收入" : "支出")
                            + "方向），请先在分类页建好再导入");
        }

        BigDecimal amount = parseAmount(row.amount());
        String note = StringUtils.hasText(row.note()) ? row.note().trim() : null;
        if (note != null && note.length() > NOTE_MAX) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "备注不能超过 " + NOTE_MAX + " 字");
        }

        Transaction transaction = new Transaction();
        transaction.setBookId(bookId);
        transaction.setCategoryId(categoryId);
        transaction.setType(type);
        transaction.setAmount(amount);
        transaction.setTransDate(transDate);
        transaction.setNote(note);
        return transaction;
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
        String value = raw == null ? "" : raw.trim();
        BigDecimal amount;
        try {
            amount = new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "金额不是有效数字：" + value);
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "金额必须大于 0");
        }
        if (amount.scale() > MoneyRules.FRACTION_DIGITS) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "金额最多两位小数");
        }
        // 与记一笔同一个上限：超了会把 DECIMAL(12,2) 的累加结余顶出列宽
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

    /* ---------- 解析 ---------- */

    /** 文件里的一整行（保留原始行号，列可能是任意长度） */
    private record CellRow(int line, String[] cells) {
        String at(int index) {
            return index < cells.length && cells[index] != null ? cells[index] : "";
        }
    }

    /** 两种列结构：标准导入格式，以及我们自己导出的明细格式 */
    private enum Layout {
        /** 日期, 类型, 分类, 金额, 备注 */
        STANDARD,
        /** 日期, 分类, 摘要, 收入, 支出, 结余（导出文件的明细区） */
        EXPORTED
    }

    /** 包级可见：zip 导入要解析包内的「流水.csv」 */
    List<RawRow> parse(String filename, byte[] content) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        // 导入只支持 CSV：Excel 的版式千人千面（合并单元格、数量/单价分组表头…），
        // 强行解析只会给出满屏的「日期格式不对」。要导 Excel 就先进 Excel 另存为 CSV。
        if (!lower.endsWith(".csv")) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "导入只支持 CSV 文件；请在 Excel 里「另存为 → CSV UTF-8（逗号分隔）」后再上传");
        }
        return extractDataRows(parseCsv(content));
    }

    /**
     * 从整张表里**找到表头**再取数据。
     * <p>为什么不能假设表头在第一行：导出的 CSV 第一行是标题，下面还有汇总，
     * 真正的明细表头在后面。所以这里扫前几十行找表头，并同时认两种列结构，
     * 让「导出 CSV → 再导入」能原样转回去。
     */
    private List<RawRow> extractDataRows(List<CellRow> table) {
        int headerIndex = -1;
        Layout layout = Layout.STANDARD;
        for (int i = 0; i < table.size(); i++) {
            CellRow row = table.get(i);
            if (!"日期".equals(row.at(0).trim())) {
                continue;
            }
            String second = row.at(1).trim();
            if ("类型".equals(second)) {
                headerIndex = i;
                layout = Layout.STANDARD;
                break;
            }
            if ("分类".equals(second)) {
                headerIndex = i;
                layout = Layout.EXPORTED;
                break;
            }
        }
        if (headerIndex < 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "没找到表头：需要「日期, 类型, 分类, 金额, 备注」，或本系统导出的「日期, 分类, 摘要, 收入, 支出」");
        }

        // 导出格式的列顺序可能变，按表头名字定位列，比写死下标稳
        CellRow header = table.get(headerIndex);
        int dateCol = columnOf(header, 0, "日期");
        int categoryCol = layout == Layout.STANDARD ? columnOf(header, 2, "分类") : columnOf(header, 1, "分类");
        int typeCol = layout == Layout.STANDARD ? columnOf(header, 1, "类型") : -1;
        int amountCol = layout == Layout.STANDARD ? columnOf(header, 3, "金额") : -1;
        int noteCol = layout == Layout.STANDARD ? columnOf(header, 4, "备注") : columnOf(header, 2, "摘要");
        int incomeCol = layout == Layout.EXPORTED ? columnOf(header, 3, "收入") : -1;
        int expenseCol = layout == Layout.EXPORTED ? columnOf(header, 4, "支出") : -1;

        List<RawRow> rows = new ArrayList<>();
        for (int i = headerIndex + 1; i < table.size(); i++) {
            CellRow row = table.get(i);
            String first = row.at(0).trim();
            // 明细区结束：合计行 / 下一个区块（分类合计）
            if ("合计".equals(first) || "分类合计".equals(first) || "分类".equals(first)) {
                break;
            }
            if (first.isEmpty() && row.at(1).trim().isEmpty() && row.at(2).trim().isEmpty()) {
                continue; // 空行
            }

            RawRow parsed;
            if (layout == Layout.STANDARD) {
                parsed = new RawRow(row.line(), row.at(dateCol), row.at(typeCol),
                        row.at(categoryCol), row.at(amountCol), row.at(noteCol));
            } else {
                // 导出的明细把收支拆成两列：哪列有值就是哪种方向
                String income = row.at(incomeCol).trim();
                String expense = row.at(expenseCol).trim();
                boolean isIncome = !income.isEmpty();
                parsed = new RawRow(row.line(), row.at(dateCol),
                        isIncome ? "收入" : "支出",
                        row.at(categoryCol),
                        isIncome ? income : expense,
                        row.at(noteCol));
                if (income.isEmpty() && expense.isEmpty()) {
                    // 收入支出都没填：这不是一笔流水，跳过（导出文件里的空行/说明行）
                    continue;
                }
            }
            if (isBlank(parsed)) {
                continue;
            }
            rows.add(parsed);
        }
        return rows;
    }

    /** 表头里找列下标；找不到就退回默认位置，兼容只有部分列的文件 */
    private static int columnOf(CellRow header, int fallback, String name) {
        for (int i = 0; i < header.cells().length; i++) {
            if (name.equals(header.at(i).trim())) {
                return i;
            }
        }
        return fallback;
    }

    private static boolean isBlank(RawRow row) {
        return !StringUtils.hasText(row.date()) && !StringUtils.hasText(row.amount())
                && !StringUtils.hasText(row.category());
    }

    private List<CellRow> parseCsv(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        // Excel 另存为 CSV 会带 BOM，不去掉第一列就永远匹配不上表头/日期
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        List<CellRow> rows = new ArrayList<>();
        for (CsvReader.Row row : CsvReader.parse(text)) {
            rows.add(new CellRow(row.line(), row.cells().toArray(new String[0])));
        }
        return rows;
    }
}