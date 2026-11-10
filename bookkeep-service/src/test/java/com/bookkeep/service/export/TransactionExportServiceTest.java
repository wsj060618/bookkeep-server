package com.bookkeep.service.export;

import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.model.entity.Book;
import com.bookkeep.service.CurrentBookService;
import com.bookkeep.service.mapper.BookMapper;
import com.bookkeep.service.mapper.TransactionMapper;
import com.bookkeep.service.mapper.row.TransactionBalanceRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CSV 生成的纯单元测试（不起 Spring、不碰数据库）。
 * <p>为什么单独测这一层：导出是「发给班群」的文件，BOM / 转义 / 逐行结余 / 期初结余错了不会有异常，
 * 只会让人在 Excel 里看到乱码或错列的表格。mapper 打桩即可，验证的是拼装逻辑本身。
 */
class TransactionExportServiceTest {

    private final TransactionMapper transactionMapper = mock(TransactionMapper.class);
    private final BookMapper bookMapper = mock(BookMapper.class);
    private final CurrentBookService currentBookService = mock(CurrentBookService.class);

    private final TransactionExportService exportService =
            new TransactionExportService(transactionMapper, bookMapper, currentBookService);

    @BeforeEach
    void setUp() {
        when(currentBookService.currentBookId()).thenReturn(1L);
        Book book = new Book();
        book.setId(1L);
        book.setName("班级账本");
        when(bookMapper.selectById(1L)).thenReturn(book);
    }

    @Test
    @DisplayName("全量导出：BOM、抬头汇总、逐行结余、合计、分类合计（支出在前）")
    void exportFullCsv() {
        when(transactionMapper.selectRangeWithRunningBalance(1L, null, null)).thenReturn(List.of(
                row("2026-09-05", "income", "1900.00", "收取班费", "全班 38 人 × 50", "1900.00"),
                row("2026-10-12", "expense", "620.00", "聚餐", "中秋聚餐", "1280.00"),
                row("2026-11-08", "expense", "86.50", "班会物资", "横幅,气球", "1193.50")));
        when(transactionMapper.sumBalance(1L)).thenReturn(new BigDecimal("1193.50"));

        String csv = toCsv(exportService, null, null);

        assertTrue(csv.startsWith("\uFEFF"), "必须带 UTF-8 BOM");
        assertTrue(csv.contains("账本名,班级账本"));
        assertTrue(csv.contains("统计区间,全部"));
        assertTrue(csv.contains("期间收入,1900.00"));
        assertTrue(csv.contains("期间支出,706.50"));
        assertTrue(csv.contains("期初结余,0.00"));
        assertTrue(csv.contains("期末结余,1193.50"));

        assertTrue(csv.contains("日期,分类,摘要,收入,支出,结余"));
        assertTrue(csv.contains("2026-09-05,收取班费,全班 38 人 × 50,1900.00,,1900.00"));
        assertTrue(csv.contains("2026-10-12,聚餐,中秋聚餐,,620.00,1280.00"));
        // 备注里的逗号必须让整格加引号，否则 Excel 会把这一行拆成多列
        assertTrue(csv.contains("2026-11-08,班会物资,\"横幅,气球\",,86.50,1193.50"));
        assertTrue(csv.contains("合计,,,1900.00,706.50,1193.50"));

        assertTrue(csv.contains("分类合计"));
        // 支出在前、金额降序；收入在后
        assertTrue(csv.indexOf("聚餐,支出,620.00") < csv.indexOf("班会物资,支出,86.50"));
        assertTrue(csv.indexOf("班会物资,支出,86.50") < csv.indexOf("收取班费,收入,1900.00"));
    }

    @Test
    @DisplayName("双引号按 CSV 规则双写，并被整体加引号")
    void quotesAreEscaped() {
        when(transactionMapper.selectRangeWithRunningBalance(anyLong(), isNull(), isNull()))
                .thenReturn(List.of(row("2026-09-06", "expense", "10.00", "聚餐", "他说\"好\"", "10.00")));
        when(transactionMapper.sumBalance(1L)).thenReturn(new BigDecimal("10.00"));

        String csv = toCsv(exportService, null, null);

        assertTrue(csv.contains("\"他说\"\"好\"\"\""), "实际内容：\n" + csv);
    }

    @Test
    @DisplayName("按区间导出：期初取「区间前一天」的累计余额，期末取区间末的累计余额")
    void exportDateRange() {
        LocalDate start = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2026, 10, 31);
        when(transactionMapper.selectRangeWithRunningBalance(1L, start, end))
                .thenReturn(List.of(row("2026-10-12", "expense", "620.00", "聚餐", null, "1280.00")));
        // 期初：截至 9-30；期末：截至 10-31
        when(transactionMapper.sumBalanceUpTo(1L, LocalDate.of(2026, 9, 30))).thenReturn(new BigDecimal("1900.00"));
        when(transactionMapper.sumBalanceUpTo(1L, end)).thenReturn(new BigDecimal("1280.00"));

        TransactionExportService.ExportPlan plan = exportService.prepare(start, end);
        String csv = toCsv(exportService, start, end);

        assertEquals("班级账本_部分_20261001-20261031.csv", plan.filename());
        assertTrue(csv.contains("统计区间,2026-10-01 至 2026-10-31"));
        assertTrue(csv.contains("期间收入,0.00"));
        assertTrue(csv.contains("期间支出,620.00"));
        assertTrue(csv.contains("期初结余,1900.00"));
        assertTrue(csv.contains("期末结余,1280.00"));
        assertFalse(csv.contains("2026-09-05"), "区间外的流水不能出现在明细里");
    }

    @Test
    @DisplayName("开始日期晚于结束日期：抛业务异常（不让它生成一份空文件）")
    void reversedRangeRejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> exportService.prepare(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 11, 1)));
        assertEquals("开始日期不能晚于结束日期", e.getMessage());
    }

    @Test
    @DisplayName("空账本：没有明细也要有表头、合计与期末结余 0.00")
    void emptyExport() {
        when(transactionMapper.selectRangeWithRunningBalance(anyLong(), any(), any())).thenReturn(List.of());
        when(transactionMapper.sumBalance(1L)).thenReturn(BigDecimal.ZERO);

        String csv = toCsv(exportService, null, null);

        assertTrue(csv.startsWith("\uFEFF"));
        assertTrue(csv.contains("日期,分类,摘要,收入,支出,结余"));
        assertTrue(csv.contains("合计,,,0.00,0.00,0.00"));
        assertTrue(csv.contains("期末结余,0.00"));
    }

    // ---------- 辅助方法 ----------

    private static String toCsv(TransactionExportService exportService, LocalDate start, LocalDate end) {
        TransactionExportService.ExportPlan plan = exportService.prepare(start, end);
        StringWriter writer = new StringWriter();
        try {
            exportService.writeCsv(plan, writer);
        } catch (IOException e) {
            // 写内存里的 StringWriter 不会真的失败；包成非受检异常，测试方法就不用声明 throws
            throw new UncheckedIOException(e);
        }
        return writer.toString();
    }

    private static TransactionBalanceRow row(String date, String type, String amount, String categoryName,
                                             String note, String balanceAfter) {
        TransactionBalanceRow row = new TransactionBalanceRow();
        row.setTransDate(LocalDate.parse(date));
        row.setType(type);
        row.setAmount(new BigDecimal(amount));
        row.setCategoryName(categoryName);
        row.setCategoryIcon("other");
        row.setCategoryColor("gray");
        row.setNote(note);
        row.setBalanceAfter(new BigDecimal(balanceAfter));
        return row;
    }
}