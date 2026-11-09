package com.bookkeep.service.export;

import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.model.entity.Book;
import com.bookkeep.service.CurrentBookService;
import com.bookkeep.service.constant.CategoryPresets;
import com.bookkeep.service.mapper.BookMapper;
import com.bookkeep.service.mapper.TransactionMapper;
import com.bookkeep.service.mapper.row.TransactionBalanceRow;
import com.bookkeep.service.support.StatisticCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 班费收支表导出（换届交接与公示用，PLAN §5.5.1）。
 * <p>先用 CSV 而不是真 Excel：UTF-8 BOM + 两位小数 + yyyy-MM-dd，Excel 双击即可打开且中文不乱码，
 * 零新依赖。真要是以后要「双 sheet」，再引 easyexcel，不必卡住主线。
 * <p>返回 {@code byte[]} 而不是写 {@code HttpServletResponse}：service 模块不依赖 web，
 * 文件流由 controller 决定怎么下发。
 * <p>逐行结余由窗口函数在<strong>全账本</strong>范围算好（见 TransactionMapper.xml），
 * 所以「只导出某几个月」时，第一行的结余仍是真实余额，不是从 0 起算。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionExportService {

    /** Excel 只有看到 BOM 才按 UTF-8 解析，否则中文乱码——这是硬要求，不是可选项。 */
    private static final String BOM = "\uFEFF";

    /** CSV 用 CRLF：Windows 上的 Excel 对 \n 也能认，但双平台都规矩一点。 */
    private static final String EOL = "\r\n";

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final TransactionMapper transactionMapper;
    private final BookMapper bookMapper;
    private final CurrentBookService currentBookService;

    /** 生成好的文件：文件名与内容分开返回，controller 负责 Content-Disposition。 */
    public record CsvFile(String filename, byte[] content) {
    }

    /**
     * @param startDate 起始日期（含），null 表示不限
     * @param endDate   结束日期（含），null 表示不限
     */
    @Transactional(readOnly = true)
    public CsvFile export(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "开始日期不能晚于结束日期");
        }

        Long bookId = currentBookService.currentBookId();
        Book book = bookMapper.selectById(bookId);
        String bookName = book == null ? CategoryPresets.DEFAULT_BOOK_NAME : book.getName();

        List<TransactionBalanceRow> rows = transactionMapper.selectRangeWithRunningBalance(bookId, startDate, endDate);
        BigDecimal opening = startDate == null
                ? ZERO
                : StatisticCalculator.money(transactionMapper.sumBalanceUpTo(bookId, startDate.minusDays(1)));
        BigDecimal closing = endDate == null
                ? StatisticCalculator.money(transactionMapper.sumBalance(bookId))
                : StatisticCalculator.money(transactionMapper.sumBalanceUpTo(bookId, endDate));

        BigDecimal totalIncome = ZERO;
        BigDecimal totalExpense = ZERO;
        // 分类合计直接从本次导出的行里累加：行集已经是「区间内全部流水」，不必再查一次库
        Map<CategoryKey, BigDecimal> categoryTotals = new LinkedHashMap<>();
        for (TransactionBalanceRow row : rows) {
            BigDecimal amount = StatisticCalculator.money(row.getAmount());
            if (CategoryPresets.TYPE_INCOME.equals(row.getType())) {
                totalIncome = totalIncome.add(amount);
            } else {
                totalExpense = totalExpense.add(amount);
            }
            categoryTotals.merge(new CategoryKey(row.getType(), row.getCategoryName()), amount, BigDecimal::add);
        }

        StringBuilder csv = new StringBuilder(BOM);
        // ---- 抬头与汇总 ----
        line(csv, bookName + " 班费收支明细");
        line(csv, "账本名", bookName);
        line(csv, "导出时间", STAMP.format(LocalDateTime.now(ZONE)));
        line(csv, "统计区间", rangeText(startDate, endDate));
        line(csv, "期间收入", text(totalIncome));
        line(csv, "期间支出", text(totalExpense));
        line(csv, "期初结余", text(opening));
        line(csv, "期末结余", text(closing));
        line(csv);

        // ---- 明细（逐行结余） ----
        line(csv, "日期", "分类", "摘要", "收入", "支出", "结余");
        for (TransactionBalanceRow row : rows) {
            boolean income = CategoryPresets.TYPE_INCOME.equals(row.getType());
            line(csv,
                    row.getTransDate().toString(),          // LocalDate.toString() 就是 yyyy-MM-dd
                    row.getCategoryName(),
                    row.getNote(),
                    income ? text(row.getAmount()) : null,
                    income ? null : text(row.getAmount()),
                    text(row.getBalanceAfter()));
        }
        line(csv, "合计", null, null, text(totalIncome), text(totalExpense), text(closing));

        // ---- 分类合计 ----
        line(csv);
        line(csv, "分类合计");
        line(csv, "分类", "类型", "金额");
        if (categoryTotals.isEmpty()) {
            line(csv, "（无）", null, text(ZERO));
        } else {
            sortedCategoryTotals(categoryTotals).forEach(entry -> line(csv,
                    entry.getKey().name(),
                    typeLabel(entry.getKey().type()),
                    text(entry.getValue())));
        }

        String range = startDate == null && endDate == null
                ? "全部"
                : (startDate == null ? "最初" : COMPACT_DATE.format(startDate))
                  + "-"
                  + (endDate == null ? "至今" : COMPACT_DATE.format(endDate));
        String filename = "班费流水_" + range + ".csv";

        log.info("导出流水：bookId={}, 起始={}, 结束={}, 行数={}", bookId, startDate, endDate, rows.size());
        return new CsvFile(filename, csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---------- 内部方法 ----------

    private record CategoryKey(String type, String name) {
    }

    /** 分类合计排序：支出在前（这是班费表的主要矛盾），同类内金额降序。 */
    private static List<Map.Entry<CategoryKey, BigDecimal>> sortedCategoryTotals(
            Map<CategoryKey, BigDecimal> totals) {
        List<Map.Entry<CategoryKey, BigDecimal>> entries = new ArrayList<>(totals.entrySet());
        entries.sort(Comparator
                .comparing((Map.Entry<CategoryKey, BigDecimal> e) -> !CategoryPresets.TYPE_EXPENSE.equals(e.getKey().type()))
                .thenComparing(Map.Entry::getValue, Comparator.reverseOrder()));
        return entries;
    }

    private static String typeLabel(String type) {
        return CategoryPresets.TYPE_INCOME.equals(type) ? "收入" : "支出";
    }

    private static String rangeText(LocalDate startDate, LocalDate endDate) {
        if (startDate == null && endDate == null) {
            return "全部";
        }
        return (startDate == null ? "最初" : startDate.toString())
                + " 至 "
                + (endDate == null ? "至今" : endDate.toString());
    }

    /** 金额统一两位小数；空单元格返回 null（由 cell 输出成空串，而不是 "null"）。 */
    private static String text(BigDecimal value) {
        return value == null ? null : StatisticCalculator.money(value).toPlainString();
    }

    private static void line(StringBuilder csv, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(cell(cells[i]));
        }
        csv.append(EOL);
    }

    /**
     * CSV 转义：备注里出现逗号、引号、换行时必须整体加引号，内部引号写两遍。
     * 少了这一步，一条「横幅,气球」的备注就会把整行挤错一列，而且只在特定数据下才暴露。
     */
    private static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean needQuote = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        return needQuote ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }
}