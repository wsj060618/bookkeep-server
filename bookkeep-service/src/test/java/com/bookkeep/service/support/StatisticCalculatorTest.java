package com.bookkeep.service.support;

import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.model.dto.resp.CategoryStatResp;
import com.bookkeep.model.dto.resp.RangeSummaryResp;
import com.bookkeep.model.dto.resp.SummaryResp;
import com.bookkeep.service.mapper.TransactionMapper;
import com.bookkeep.service.mapper.row.CategoryAmountRow;
import com.bookkeep.service.mapper.row.MonthSumRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 统计口径的纯单元测试（不起 Spring、不碰数据库）。
 * <p>钉住：金额两位小数、占比两位小数且不除零、区间两端可空、期末结余取「结束月份最后一天」。
 */
class StatisticCalculatorTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final TransactionMapper transactionMapper = mock(TransactionMapper.class);

    private final StatisticCalculator calculator = new StatisticCalculator(transactionMapper);

    @Test
    @DisplayName("month 不传取当月（北京时间），非法格式抛 400 而不是返回空数据")
    void resolveMonth() {
        assertEquals(YearMonth.now(ZONE), StatisticCalculator.resolveMonth(null));
        assertEquals(YearMonth.now(ZONE), StatisticCalculator.resolveMonth("  "));
        assertEquals(YearMonth.of(2026, 8), StatisticCalculator.resolveMonth("2026-08"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> StatisticCalculator.resolveMonth("2026-8"));
        assertEquals("月份格式应为 yyyy-MM，例如 2026-08", e.getMessage());
        assertThrows(BusinessException.class, () -> StatisticCalculator.resolveMonth("2026-13"));
    }

    @Test
    @DisplayName("区间两端都可空；开始晚于结束 → 400")
    void resolveRange() {
        StatisticCalculator.MonthRange all = StatisticCalculator.resolveRange(null, null);
        assertNull(all.start());
        assertNull(all.end());
        assertNull(all.startDate());
        assertNull(all.endDate());

        StatisticCalculator.MonthRange onlyEnd = StatisticCalculator.resolveRange(null, "2026-10");
        assertNull(onlyEnd.startDate());
        assertEquals(LocalDate.of(2026, 10, 31), onlyEnd.endDate());

        StatisticCalculator.MonthRange range =
                StatisticCalculator.resolveRange("2026-09", "2026-11");
        assertEquals(LocalDate.of(2026, 9, 1), range.startDate());
        assertEquals(LocalDate.of(2026, 11, 30), range.endDate());

        BusinessException e = assertThrows(BusinessException.class,
                () -> StatisticCalculator.resolveRange("2026-11", "2026-09"));
        assertEquals("开始月份不能晚于结束月份", e.getMessage());
        assertThrows(BusinessException.class, () -> StatisticCalculator.resolveRange("2026-9", null));
    }

    @Test
    @DisplayName("区间汇总：结余取结束月份最后一天；结束月份不限时取当前余额")
    void summaryBetween() {
        MonthSumRow row = new MonthSumRow();
        row.setIncome(new BigDecimal("1900.00"));
        row.setExpense(new BigDecimal("620.00"));
        when(transactionMapper.sumRange(1L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 11, 30)))
                .thenReturn(row);
        when(transactionMapper.sumBalanceUpTo(1L, LocalDate.of(2026, 11, 30)))
                .thenReturn(new BigDecimal("1280.00"));

        RangeSummaryResp summary = calculator.summaryBetween(1L,
                StatisticCalculator.resolveRange("2026-09", "2026-11"));

        assertEquals("2026-09", summary.startMonth());
        assertEquals("2026-11", summary.endMonth());
        assertEquals("1900.00", summary.income().toPlainString());
        assertEquals("620.00", summary.expense().toPlainString());
        assertEquals("1280.00", summary.balance().toPlainString());

        // 不传区间 = 全部：收入支出用无限定查询，结余取当前余额
        when(transactionMapper.sumRange(1L, null, null)).thenReturn(null);
        when(transactionMapper.sumBalance(1L)).thenReturn(new BigDecimal("2560.00"));
        RangeSummaryResp all = calculator.summaryBetween(1L, StatisticCalculator.resolveRange(null, null));
        assertNull(all.startMonth());
        assertNull(all.endMonth());
        assertEquals("0.00", all.income().toPlainString());
        assertEquals("0.00", all.expense().toPlainString());
        assertEquals("2560.00", all.balance().toPlainString());
    }

    @Test
    @DisplayName("单月汇总（公示页）：本月结余与累计余额分开，空月份补 0.00")
    void summaryOf() {
        MonthSumRow row = new MonthSumRow();
        row.setIncome(new BigDecimal("1900.00"));
        row.setExpense(new BigDecimal("620.00"));
        when(transactionMapper.sumRange(1L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)))
                .thenReturn(row);
        when(transactionMapper.sumBalance(1L)).thenReturn(new BigDecimal("1280.00"));

        SummaryResp summary = calculator.summaryOf(1L, YearMonth.of(2026, 9));

        assertEquals("2026-09", summary.month());
        assertEquals("1900.00", summary.income().toPlainString());
        assertEquals("620.00", summary.expense().toPlainString());
        assertEquals("1280.00", summary.balance().toPlainString());
        assertEquals("1280.00", summary.totalBalance().toPlainString());

        when(transactionMapper.sumRange(1L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
                .thenReturn(null);
        when(transactionMapper.sumBalance(1L)).thenReturn(BigDecimal.ZERO);
        SummaryResp empty = calculator.summaryOf(1L, YearMonth.of(2026, 1));
        assertEquals("0.00", empty.income().toPlainString());
        assertEquals("0.00", empty.balance().toPlainString());
        assertEquals("0.00", empty.totalBalance().toPlainString());
    }

    @Test
    @DisplayName("分类占比：金额降序、percent 两位小数、总金额为 0 时占比 0.00")
    void categoriesBetween() {
        CategoryAmountRow dining = categoryRow(3L, "聚餐", new BigDecimal("620.00"));
        CategoryAmountRow activity = categoryRow(4L, "活动经费", new BigDecimal("86.50"));
        when(transactionMapper.selectCategoryAmountsRange(
                1L, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), "expense"))
                .thenReturn(List.of(dining, activity));

        List<CategoryStatResp> stats = calculator.categoriesOf(1L, YearMonth.of(2026, 11), "expense");

        assertEquals(2, stats.size());
        assertEquals("聚餐", stats.get(0).categoryName());
        assertEquals("620.00", stats.get(0).amount().toPlainString());
        assertEquals("87.76", stats.get(0).percent().toPlainString());
        assertEquals("12.24", stats.get(1).percent().toPlainString());

        // 总金额为 0（空月份）时不能除零
        when(transactionMapper.selectCategoryAmountsRange(1L, null, null, "expense"))
                .thenReturn(List.of(categoryRow(3L, "聚餐", BigDecimal.ZERO)));
        assertEquals("0.00", calculator.categoriesBetween(1L,
                StatisticCalculator.resolveRange(null, null), "expense").get(0).percent().toPlainString());
    }

    private static CategoryAmountRow categoryRow(Long id, String name, BigDecimal amount) {
        CategoryAmountRow row = new CategoryAmountRow();
        row.setCategoryId(id);
        row.setCategoryName(name);
        row.setCategoryIcon("other");
        row.setCategoryColor("gray");
        row.setAmount(amount);
        return row;
    }
}