package com.bookkeep.service.support;

import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.model.dto.resp.CategoryStatResp;
import com.bookkeep.model.dto.resp.RangeSummaryResp;
import com.bookkeep.service.mapper.TransactionMapper;
import com.bookkeep.service.mapper.row.CategoryAmountRow;
import com.bookkeep.service.mapper.row.MonthSumRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 统计口径的唯一实现点。统计接口和公示页都走这里，只是 bookId 来源不同、区间粒度不同
 * （统计页按月 {@link MonthRange}，公示页按天 {@link DateRange}）。
 */
@Component
@RequiredArgsConstructor
public class StatisticCalculator {

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd");

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final TransactionMapper transactionMapper;

    /** 月份区间；两端可空，null 表示该端不限。 */
    public record MonthRange(YearMonth start, YearMonth end) {

        public LocalDate startDate() {
            return start == null ? null : start.atDay(1);
        }

        public LocalDate endDate() {
            return end == null ? null : end.atEndOfMonth();
        }
    }

    /** 日区间（公示页）；两端可空，null 表示该端不限。 */
    public record DateRange(LocalDate start, LocalDate end) {

        public LocalDate startDate() {
            return start;
        }

        public LocalDate endDate() {
            return end;
        }
    }

    /** 区间金额：公示页只要数字，不需要月份回显，所以和 {@link RangeSummaryResp} 分开。 */
    public record Amounts(BigDecimal income, BigDecimal expense, BigDecimal balance) {
    }

    /**
     * 解析 {@code startMonth} / {@code endMonth}（{@code yyyy-MM}，都可空）。
     * <p>格式错或开始晚于结束都抛 400，绝不静默返回空数据。
     */
    public static MonthRange resolveRange(String startMonth, String endMonth) {
        YearMonth start = parseMonth(startMonth);
        YearMonth end = parseMonth(endMonth);
        if (start != null && end != null && start.isAfter(end)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "开始月份不能晚于结束月份");
        }
        return new MonthRange(start, end);
    }

    /**
     * 解析 {@code startDate} / {@code endDate}（{@code yyyy-MM-dd}，都可空）。
     * <p>格式错或开始晚于结束都抛 400，绝不静默返回空数据。
     */
    public static DateRange resolveDateRange(String startDate, String endDate) {
        LocalDate start = parseDate(startDate);
        LocalDate end = parseDate(endDate);
        if (start != null && end != null && start.isAfter(end)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "开始日期不能晚于结束日期");
        }
        return new DateRange(start, end);
    }

    private static YearMonth parseMonth(String month) {
        if (!StringUtils.hasText(month)) {
            return null;
        }
        try {
            return YearMonth.parse(month.trim(), MONTH_FORMAT);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "月份格式应为 yyyy-MM，例如 2026-08");
        }
    }

    private static LocalDate parseDate(String date) {
        if (!StringUtils.hasText(date)) {
            return null;
        }
        try {
            return LocalDate.parse(date.trim(), DATE_FORMAT);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "日期格式应为 yyyy-MM-dd，例如 2026-08-01");
        }
    }

    /** 区间收入 / 支出 / 期末结余（结束月份不限时 = 当前余额）。 */
    public RangeSummaryResp summaryBetween(Long bookId, MonthRange range) {
        Amounts amounts = amountsBetween(bookId, range.startDate(), range.endDate());
        return new RangeSummaryResp(
                range.start() == null ? null : range.start().format(MONTH_FORMAT),
                range.end() == null ? null : range.end().format(MONTH_FORMAT),
                amounts.income(),
                amounts.expense(),
                amounts.balance());
    }

    public Amounts amountsBetween(Long bookId, DateRange range) {
        return amountsBetween(bookId, range.startDate(), range.endDate());
    }

    private Amounts amountsBetween(Long bookId, LocalDate startDate, LocalDate endDate) {
        MonthSumRow row = transactionMapper.sumRange(bookId, startDate, endDate);
        BigDecimal income = money(row == null ? null : row.getIncome());
        BigDecimal expense = money(row == null ? null : row.getExpense());
        // 结余取「截至结束日」的全账本余额；结束日不限 = 当前余额
        BigDecimal balance = money(endDate == null
                ? transactionMapper.sumBalance(bookId)
                : transactionMapper.sumBalanceUpTo(bookId, endDate));
        return new Amounts(income, expense, balance);
    }

    /** 区间的分类占比，金额降序；总金额为 0 时占比一律 0.00。 */
    public List<CategoryStatResp> categoriesBetween(Long bookId, MonthRange range, String type) {
        return categoriesBetween(bookId, range.startDate(), range.endDate(), type);
    }

    public List<CategoryStatResp> categoriesBetween(Long bookId, DateRange range, String type) {
        return categoriesBetween(bookId, range.startDate(), range.endDate(), type);
    }

    private List<CategoryStatResp> categoriesBetween(Long bookId, LocalDate startDate, LocalDate endDate,
                                                     String type) {
        List<CategoryAmountRow> rows = transactionMapper.selectCategoryAmountsRange(bookId, startDate, endDate, type);
        return toCategoryStats(rows);
    }

    private static List<CategoryStatResp> toCategoryStats(List<CategoryAmountRow> rows) {
        BigDecimal total = rows.stream()
                .map(row -> money(row.getAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return rows.stream()
                .map(row -> new CategoryStatResp(
                        row.getCategoryId(),
                        row.getCategoryName(),
                        row.getCategoryIcon(),
                        row.getCategoryColor(),
                        money(row.getAmount()),
                        percent(row.getAmount(), total)))
                .toList();
    }

    /** 金额统一两位小数：SUM 空集返回的 0 是 scale=0，直接序列化会变成 "0"。 */
    public static BigDecimal money(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal percent(BigDecimal amount, BigDecimal total) {
        if (total.signum() == 0) {
            return ZERO;
        }
        return money(amount).multiply(BigDecimal.valueOf(100))
                .divide(total, 2, RoundingMode.HALF_UP);
    }
}