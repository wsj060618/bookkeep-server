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

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
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
 * <p>导出 CSV 给 Excel 看：UTF-8 BOM + 两位小数 + yyyy-MM-dd；Excel 双击就出中文表格。
 * 要真 xlsx（双 sheet）再引 easyexcel，先卡住主线。
 * <p><strong>流式写</strong>：{@link #prepare} 只做校验 + 取数（出错还能返回正常 JSON 信封），
 * 之后由 controller 把响应体交给 {@link #writeCsv} 逐行写到输出流，不再在内存里拼一个
 * {@code byte[]}（几千条流水时那是整份文件的 2 份拷贝）。
 * <p>逐行结余在 <strong>全账本</strong>范围内算（见 TransactionMapper.xml）：
 * 只导出某个月时，第一行的结余也是真实余额，不是从 0 开始。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionExportService {

    /** Excel 只有看到 BOM 才按 UTF-8 读，否则中文乱码——硬要求，不是可选项 */
    private static final String BOM = "\uFEFF";

    /** CSV 用 CRLF：Windows 上的 Excel 对 \n 也认，但双平台统一用这个 */
    private static final String EOL = "\r\n";

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final TransactionMapper transactionMapper;
    private final BookMapper bookMapper;
    private final CurrentBookService currentBookService;

    /** 分类合计的一行（已排序，支出在前） */
    public record CategoryTotal(String categoryName, String type, BigDecimal amount) {
    }

    /**
     * 已算好的导出内容：只含数据，不含任何 IO。
     * <p>把「取数」和「写文件」分开，是为了让参数错误能在响应提交之前抛出（仍走正常 JSON 信封）。
     */
    public record ExportPlan(
            String filename,
            String title,
            String bookName,
            String generatedAt,
            String rangeText,
            BigDecimal totalIncome,
            BigDecimal totalExpense,
            BigDecimal opening,
            BigDecimal closing,
            List<TransactionBalanceRow> rows,
            List<CategoryTotal> categoryTotals
    ) {
    }

    /**
     * 校验 + 取数。**任何参数错误都在这里抛出，此时响应还没开始写。**
     *
     * @param startDate 开始日期，含；null 表示不限
     * @param endDate   结束日期，含；null 表示不限
     */
    @Transactional(readOnly = true)
    public ExportPlan prepare(LocalDate startDate, LocalDate endDate) {
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
        // 分类合计直接从本次查出的明细累加：中间已经是「全账本」口径的流水，不必再查一次
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

        // 文件名就用账本名 + 全部/部分：导出的文件直接以账本命名，发群不会认错
        String suffix = startDate == null && endDate == null
                ? "全部"
                : "部分_" + (startDate == null ? "起" : COMPACT_DATE.format(startDate))
                  + "-"
                  + (endDate == null ? "今" : COMPACT_DATE.format(endDate));

        log.info("导出流水：bookId={}, 开始={}, 结束={}, 条数={}", bookId, startDate, endDate, rows.size());
        return new ExportPlan(
                safeFileName(bookName) + "_" + suffix + ".csv",
                bookName + " 收支明细",
                bookName,
                STAMP.format(LocalDateTime.now(ZONE)),
                rangeText(startDate, endDate),
                totalIncome,
                totalExpense,
                opening,
                closing,
                rows,
                sortedCategoryTotals(categoryTotals));
    }

    /**
     * 把计划逐行写进响应流（调用方负责给 UTF-8 的 Writer）。
     * <p>只 flush 不 close：底层的 ServletOutputStream 由容器关。
     */
    public void writeCsv(ExportPlan plan, Writer out) throws IOException {
        out.write(BOM);

        // ---- 抬头汇总 ----
        line(out, plan.title());
        line(out, "账本名", plan.bookName());
        line(out, "导出时间", plan.generatedAt());
        line(out, "统计区间", plan.rangeText());
        line(out, "期间收入", text(plan.totalIncome()));
        line(out, "期间支出", text(plan.totalExpense()));
        line(out, "期初结余", text(plan.opening()));
        line(out, "期末结余", text(plan.closing()));
        line(out);

        // ---- 明细（含逐行结余） ----
        line(out, "日期", "分类", "摘要", "收入", "支出", "结余");
        for (TransactionBalanceRow row : plan.rows()) {
            boolean income = CategoryPresets.TYPE_INCOME.equals(row.getType());
            line(out,
                    row.getTransDate().toString(),          // LocalDate.toString() 就是 yyyy-MM-dd
                    row.getCategoryName(),
                    row.getNote(),
                    income ? text(row.getAmount()) : null,
                    income ? null : text(row.getAmount()),
                    text(row.getBalanceAfter()));
        }
        line(out, "合计", null, null,
                text(plan.totalIncome()), text(plan.totalExpense()), text(plan.closing()));

        // ---- 分类合计 ----
        line(out);
        line(out, "分类合计");
        line(out, "分类", "类型", "金额");
        if (plan.categoryTotals().isEmpty()) {
            line(out, "（无）", null, text(ZERO));
        } else {
            for (CategoryTotal total : plan.categoryTotals()) {
                line(out, total.categoryName(), typeLabel(total.type()), text(total.amount()));
            }
        }
        out.flush();
    }

    // ---------- 内部方法 ----------

    private record CategoryKey(String type, String name) {
    }

    /** 分类合计先支出后收入（和班费表要表达的「钱花哪了」优先一致），同类内按金额降序 */
    private static List<CategoryTotal> sortedCategoryTotals(Map<CategoryKey, BigDecimal> totals) {
        List<Map.Entry<CategoryKey, BigDecimal>> entries = new ArrayList<>(totals.entrySet());
        entries.sort(Comparator
                .comparing((Map.Entry<CategoryKey, BigDecimal> e) -> !CategoryPresets.TYPE_EXPENSE.equals(e.getKey().type()))
                .thenComparing(Map.Entry::getValue, Comparator.reverseOrder()));
        return entries.stream()
                .map(e -> new CategoryTotal(e.getKey().name(), e.getKey().type(), e.getValue()))
                .toList();
    }

    private static String typeLabel(String type) {
        return CategoryPresets.TYPE_INCOME.equals(type) ? "收入" : "支出";
    }

    private static String rangeText(LocalDate startDate, LocalDate endDate) {
        if (startDate == null && endDate == null) {
            return "全部";
        }
        return (startDate == null ? "最早" : startDate.toString())
                + " 至 "
                + (endDate == null ? "今天" : endDate.toString());
    }

    /** 文件名里不能用 Windows 的保留字符，也不能太长：替换掉，保留中文与数字 */
    private static String safeFileName(String name) {
        String cleaned = name == null ? "班费" : name.trim();
        cleaned = cleaned.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        if (cleaned.isEmpty()) {
            cleaned = "班费";
        }
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }

    /** 金额统一两位小数；空单元格返回 null，由 cell 写成空串（不能出现 "null"） */
    private static String text(BigDecimal value) {
        return value == null ? null : StatisticCalculator.money(value).toPlainString();
    }

    private static void line(Writer out, String... cells) throws IOException {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.write(',');
            }
            out.write(cell(cells[i]));
        }
        out.write(EOL);
    }

    /**
     * CSV 转义：备注里有逗号、引号、换行时整体加引号，内部引号写两遍。
     * 少这一步，一条「横幅,气球」的备注就会把表格挤错列。
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