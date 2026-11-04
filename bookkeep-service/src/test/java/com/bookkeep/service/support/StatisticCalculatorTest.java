package com.bookkeep.service.support;

import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.model.dto.resp.CategoryStatResp;
import com.bookkeep.model.dto.resp.SummaryResp;
import com.bookkeep.service.mapper.TransactionMapper;
import com.bookkeep.service.mapper.row.CategoryAmountRow;
import com.bookkeep.service.mapper.row.MonthSumRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 统计口径的纯单元测试（不起 Spring、不碰数据库）。
 * <p>重点钉住两件容易被「看起来对」掩盖的事：金额必须补到两位小数（否则同一接口里会出现 "0" 和 "0.00"），
 * 占比必须两位小数且总金额为 0 时不抛除零异常。
 */
class StatisticCalculatorTest {

    private final TransactionMapper transactionMapper = mock(TransactionMapper.class);

    private final StatisticCalculator calculator = new StatisticCalculator(transactionMapper);

    @Test
    @DisplayName("month 不传取当月（北京时间），非法格式抛 400 而不是返回空数据")
    void resolveMonth() {
        assertEquals(YearMonth.now(ZoneId.of("Asia/Shanghai")), StatisticCalculator.resolveMonth(null));
        assertEquals(YearMonth.now(ZoneId.of("Asia/Shanghai")), StatisticCalculator.resolveMonth("  "));
        assertEquals(YearMonth.of(2026, 8), StatisticCalculator.resolveMonth("2026-08"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> StatisticCalculator.resolveMonth("2026-8"));
        assertEquals("月份格式应为 yyyy-MM，例如 2026-08", e.getMessage());
        assertThrows(BusinessException.class, () -> StatisticCalculator.resolveMonth("2026-13"));
    }

    @Test
    @DisplayName("月度汇总：本月结余与累计余额分开，空月份的两个 0 都是 0.00")
    void summaryOf() {
        MonthSumRow row = new MonthSumRow();
        row.setIncome(new BigDecimal("1900.00"));
        row.setExpense(new BigDecimal("620.00"));
        when(transactionMapper.sumMonth(1L, "2026-09")).thenReturn(row);
        when(transactionMapper.sumBalance(1L)).thenReturn(new BigDecimal("1280.00"));

        SummaryResp summary = calculator.summaryOf(1L, YearMonth.of(2026, 9));

        assertEquals("2026-09", summary.month());
        assertEquals("1900.00", summary.income().toPlainString());
        assertEquals("620.00", summary.expense().toPlainString());
        assertEquals("1280.00", summary.balance().toPlainString());
        assertEquals("1280.00", summary.totalBalance().toPlainString());

        // 空月份：SUM 兜底成 0，必须补成 0.00 才能和别的接口一致
        when(transactionMapper.sumMonth(1L, "2026-01")).thenReturn(null);
        when(transactionMapper.sumBalance(1L)).thenReturn(BigDecimal.ZERO);
        SummaryResp empty = calculator.summaryOf(1L, YearMonth.of(2026, 1));
        assertEquals("0.00", empty.income().toPlainString());
        assertEquals("0.00", empty.balance().toPlainString());
        assertEquals("0.00", empty.totalBalance().toPlainString());
    }

    @Test
    @DisplayName("分类占比：金额降序、percent 两位小数、总金额为 0 时占比 0.00")
    void categoriesOf() {
        CategoryAmountRow dining = categoryRow(3L, "聚餐", new BigDecimal("620.00"));
        CategoryAmountRow activity = categoryRow(4L, "活动经费", new BigDecimal("86.50"));
        when(transactionMapper.selectCategoryAmounts(1L, "2026-11", "expense"))
                .thenReturn(List.of(dining, activity));

        List<CategoryStatResp> stats = calculator.categoriesOf(1L, YearMonth.of(2026, 11), "expense");

        assertEquals(2, stats.size());
        assertEquals("聚餐", stats.get(0).categoryName());
        assertEquals("620.00", stats.get(0).amount().toPlainString());
        assertEquals("87.76", stats.get(0).percent().toPlainString());
        assertEquals("12.24", stats.get(1).percent().toPlainString());

        // 总金额为 0（理论上不会出现，但空月份/全零分类时要挡住除零）
        when(transactionMapper.selectCategoryAmounts(1L, "2026-01", "expense"))
                .thenReturn(List.of(categoryRow(3L, "聚餐", BigDecimal.ZERO)));
        assertEquals("0.00", calculator.categoriesOf(1L, YearMonth.of(2026, 1), "expense")
                .get(0).percent().toPlainString());
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