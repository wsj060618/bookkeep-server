package com.bookkeep.service.support;

import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.model.dto.resp.CategoryStatResp;
import com.bookkeep.model.dto.resp.SummaryResp;
import com.bookkeep.service.mapper.TransactionMapper;
import com.bookkeep.service.mapper.row.CategoryAmountRow;
import com.bookkeep.service.mapper.row.MonthSumRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 统计口径的唯一实现点。
 * <p>统计接口（需登录）和公示页（免登录）必须给出<strong>同一组数字</strong>——
 * 同学在公示页看到的「本月支出」和生活委员在后台看到的不一致，是最难解释的一类 bug。
 * 所以两边都走这里，只是传进来的 bookId 来源不同（SecurityUtil / shareToken）。
 * <p>入参是 bookId 而不是「当前登录用户」：公示页没有登录态，不能在这里碰 {@code SecurityUtil}。
 */
@Component
@RequiredArgsConstructor
public class StatisticCalculator {

    /** 与 JacksonConfig 的 GMT+8 保持一致：请求不传月份时按北京时间取当月。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM");
    // 用 uuuu 而不是 yyyy：yyyy 是 year-of-era，严格解析要靠默认纪元补全；uuuu 是 proleptic year，解析更可靠

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final TransactionMapper transactionMapper;

    /**
     * 解析 {@code ?month=yyyy-MM}，空值取当月。
     * <p>格式错必须 400 而不是静默返回空数据：前端把 2026-8 写错时，空结果会被误读成「这个月没记账」。
     */
    public static YearMonth resolveMonth(String month) {
        if (!StringUtils.hasText(month)) {
            return YearMonth.now(ZONE);
        }
        try {
            return YearMonth.parse(month.trim(), MONTH_FORMAT);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "月份格式应为 yyyy-MM，例如 2026-08");
        }
    }

    /** 某月收入 / 支出 / 本月结余 + 全账本累计余额。 */
    public SummaryResp summaryOf(Long bookId, YearMonth month) {
        MonthSumRow row = transactionMapper.sumMonth(bookId, month.format(MONTH_FORMAT));
        BigDecimal income = money(row == null ? null : row.getIncome());
        BigDecimal expense = money(row == null ? null : row.getExpense());
        return new SummaryResp(
                month.format(MONTH_FORMAT),
                income,
                expense,
                income.subtract(expense),
                money(transactionMapper.sumBalance(bookId)));
    }

    /** 某月某类型的分类占比，金额降序；总金额为 0 时占比一律 0.00（不出现除零）。 */
    public List<CategoryStatResp> categoriesOf(Long bookId, YearMonth month, String type) {
        List<CategoryAmountRow> rows = transactionMapper.selectCategoryAmounts(
                bookId, month.format(MONTH_FORMAT), type);
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

    /** 金额统一两位小数：SUM 空集返回的 0 是 scale=0，直接序列化会变成 "0" 而不是 "0.00"。 */
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