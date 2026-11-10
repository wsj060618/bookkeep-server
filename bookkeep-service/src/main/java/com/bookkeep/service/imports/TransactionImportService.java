package com.bookkeep.service.imports;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.model.dto.resp.ImportResultResp;
import com.bookkeep.model.entity.Category;
import com.bookkeep.model.entity.Transaction;
import com.bookkeep.service.CurrentBookService;
import com.bookkeep.service.constant.CategoryPresets;
import com.bookkeep.service.mapper.CategoryMapper;
import com.bookkeep.service.mapper.TransactionMapper;
import com.bookkeep.service.support.StatisticCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 流水导入（PLAN §10.3）：CSV / xlsx，列固定为「日期, 类型, 分类, 金额, 备注」。
 * <p>三条原则：
 * <ol>
 *   <li>**行级失败不阻塞其它行**：能入的照常入，失败的行带行号与原因返回给前端</li>
 *   <li>**分类不存在就报错，绝不自动建分类**：一份脏文件不该在账本里长出一堆脏分类</li>
 *   <li>**支出仍受余额约束**：按文件顺序累加余额，入不敷出的那一行失败</li>
 * </ol>
 * <p>整份文件在一个事务里，但失败行是被 catch 掉的业务错误，不会把已成功的行回滚。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionImportService {

    /** 一次最多 2000 行：再多就该分批导，也避免一份大文件把请求拖死 */
    private static final int MAX_ROWS = 2000;

    /** 文件大小上限，和 application.yml 的 multipart 限制保持一致 */
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private static final int NOTE_MAX = 255;

    private final CurrentBookService currentBookService;
    private final TransactionMapper transactionMapper;
    private final CategoryMapper categoryMapper;

    /** 文件里的一行（已解析成字符串，未校验） */
    private record RawRow(int line, String date, String type, String category, String amount, String note) {
    }

    /**
     * @param filename 原始文件名（用来判断 csv / xlsx）
     * @param content  文件字节
     */
    @Transactional(rollbackFor = Exception.class)
    public ImportResultResp importFile(String filename, byte[] content) {
        if (content == null || content.length == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件是空的，请检查后重传");
        }
        if (content.length > MAX_BYTES) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件不能超过 5MB");
        }
        // 归档账本只读：先拦，别解析完才发现不能写
        Long bookId = currentBookService.writableBookId();

        List<RawRow> rows = parse(filename, content);
        if (rows.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件里没有可导入的数据行");
        }
        if (rows.size() > MAX_ROWS) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "一次最多导入 " + MAX_ROWS + " 行，请拆成多个文件");
        }

        Map<String, Long> categoryIds = loadCategoryIds(bookId);
        BigDecimal balance = StatisticCalculator.money(transactionMapper.sumBalance(bookId));

        int success = 0;
        List<ImportResultResp.FailedRow> failed = new ArrayList<>();
        for (RawRow row : rows) {
            try {
                Transaction transaction = toTransaction(row, bookId, categoryIds, balance);
                balance = "income".equals(transaction.getType())
                        ? balance.add(transaction.getAmount())
                        : balance.subtract(transaction.getAmount());
                transactionMapper.insert(transaction);
                success++;
            } catch (BusinessException e) {
                failed.add(new ImportResultResp.FailedRow(row.line(), e.getMessage()));
            }
        }

        log.info("导入流水：bookId={}, 文件={}, 成功 {} 行，失败 {} 行", bookId, filename, success, failed.size());
        return new ImportResultResp(success, failed);
    }

    /* ---------- 校验 + 落库 ---------- */

    private Transaction toTransaction(RawRow row, Long bookId, Map<String, Long> categoryIds, BigDecimal balance) {
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

        if (CategoryPresets.TYPE_EXPENSE.equals(type) && balance.subtract(amount).compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "余额不足：当前余额 " + StatisticCalculator.money(balance).toPlainString() + "，这笔要支出 " + amount);
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
        if (amount.scale() > 2) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "金额最多两位小数");
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

    private List<RawRow> parse(String filename, byte[] content) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        List<CellRow> table = lower.endsWith(".xlsx") || lower.endsWith(".xls")
                ? parseWorkbook(content)
                : parseCsv(content);
        return extractDataRows(table);
    }

    /**
     * 从整张表里**找到表头**再取数据。
     * <p>为什么不能假设表头在第一行：导出的 xlsx 第一行是标题，下面还有汇总和图表，
     * 真正的明细表头在第 21 行。所以这里扫前几十行找表头，并同时认两种列结构，
     * 让「导出 → 再导入」能原样转回去。
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

    private List<CellRow> parseWorkbook(byte[] content) {
        List<CellRow> rows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int i = 0; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                int width = Math.max(row.getLastCellNum(), 6);
                String[] cells = new String[width];
                for (int c = 0; c < width; c++) {
                    cells[c] = formatter.formatCellValue(row.getCell(c));
                }
                rows.add(new CellRow(i + 1, cells)); // Excel 行号从 1 开始，和用户看到的一致
            }
        } catch (IOException | RuntimeException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "Excel 解析失败，请确认文件没损坏");
        }
        return rows;
    }

    private List<CellRow> parseCsv(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        // Excel 另存为 CSV 会带 BOM，不去掉第一列就永远匹配不上表头/日期
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        List<CellRow> rows = new ArrayList<>();
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            List<String> cells = splitCsvLine(lines[i]);
            rows.add(new CellRow(i + 1, cells.toArray(new String[0])));
        }
        return rows;
    }

    private static boolean isBlank(RawRow row) {
        return !StringUtils.hasText(row.date()) && !StringUtils.hasText(row.amount())
                && !StringUtils.hasText(row.category());
    }

    /** 手写 CSV 拆列：只处理双引号与转义的双引号，够用且不引入 commons-csv 依赖 */
    private static List<String> splitCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString());
        return cells;
    }
}