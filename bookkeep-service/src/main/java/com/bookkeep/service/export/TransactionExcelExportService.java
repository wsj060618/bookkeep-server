package com.bookkeep.service.export;

import com.bookkeep.service.export.TransactionExportService.CategoryTotal;
import com.bookkeep.service.export.TransactionExportService.ExportPlan;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xddf.usermodel.chart.AxisCrosses;
import org.apache.poi.xddf.usermodel.chart.AxisPosition;
import org.apache.poi.xddf.usermodel.chart.BarDirection;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.LegendPosition;
import org.apache.poi.xddf.usermodel.chart.XDDFBarChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFCategoryAxis;
import org.apache.poi.xddf.usermodel.chart.XDDFChartLegend;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory;
import org.apache.poi.xddf.usermodel.chart.XDDFNumericalDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFValueAxis;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.util.List;

/**
 * 流水导出成 xlsx（PLAN §10.3）。
 * <p>比 CSV 多的就是「一眼能看懂」：**顶部一张汇总 + 两张分类柱状图**（跟统计页一个思路），
 * 下面才是明细与分类合计，班会上直接投屏/发群都不用再加工。
 * <p>复用 {@link TransactionExportService#prepare} 取数：CSV 与 xlsx 是同一份口径，
 * 不允许两边各算一遍（否则「导出的数和页面上的数对不上」）。
 */
@Slf4j
@Service
public class TransactionExcelExportService {

    /** 明细表从第几行开始（0 基），上面留给汇总与图表 */
    private static final int DETAIL_HEADER_ROW = 20;

    public void write(ExportPlan plan, OutputStream out) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CellStyle money = moneyStyle(workbook);
            CellStyle head = headStyle(workbook);

            XSSFSheet summarySheet = workbook.createSheet("收支表");
            int categoryChartStartRow = writeSummary(summarySheet, plan, head, money);

            XSSFSheet categorySheet = workbook.createSheet("分类合计");
            CategoryRanges ranges = writeCategoryTotals(categorySheet, plan, head, money);

            drawBarChart(summarySheet, categorySheet, ranges.expense(), "支出分类", 3);
            drawBarChart(summarySheet, categorySheet, ranges.income(), "收入分类", 10);

            writeDetail(summarySheet, plan, head, money, categoryChartStartRow);

            workbook.write(out);
            out.flush();
        }
        log.info("导出 xlsx：{}，明细 {} 条", plan.filename(), plan.rows().size());
    }

    /* ---------- 汇总区（顶部） ---------- */

    private int writeSummary(XSSFSheet sheet, ExportPlan plan, CellStyle head, CellStyle money) {
        XSSFRow title = sheet.createRow(0);
        title.createCell(0).setCellValue(plan.title());
        title.getCell(0).setCellStyle(head);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 5));

        XSSFRow row = sheet.createRow(1);
        cell(row, 0, "账本名", head);
        cell(row, 1, plan.bookName());
        row = sheet.createRow(2);
        cell(row, 0, "导出时间", head);
        cell(row, 1, plan.generatedAt());
        row = sheet.createRow(3);
        cell(row, 0, "统计区间", head);
        cell(row, 1, plan.rangeText());
        row = sheet.createRow(4);
        cell(row, 0, "期间收入", head);
        moneyCell(row, 1, plan.totalIncome(), money);
        row = sheet.createRow(5);
        cell(row, 0, "期间支出", head);
        moneyCell(row, 1, plan.totalExpense(), money);
        row = sheet.createRow(6);
        cell(row, 0, "期初结余", head);
        moneyCell(row, 1, plan.opening(), money);
        row = sheet.createRow(7);
        cell(row, 0, "期末结余", head);
        moneyCell(row, 1, plan.closing(), money);

        sheet.setColumnWidth(0, 12 * 256);
        sheet.setColumnWidth(1, 20 * 256);
        return DETAIL_HEADER_ROW;
    }

    /* ---------- 分类合计（图表数据源） ---------- */

    private record Range(int firstRow, int lastRow) {
        boolean hasData() {
            return lastRow >= firstRow;
        }
    }

    private record CategoryRanges(Range expense, Range income) {
    }

    private CategoryRanges writeCategoryTotals(XSSFSheet sheet, ExportPlan plan, CellStyle head, CellStyle money) {
        XSSFRow header = sheet.createRow(0);
        cell(header, 0, "分类", head);
        cell(header, 1, "金额", head);
        cell(header, 2, "类型", head);

        List<CategoryTotal> expense = plan.categoryTotals().stream()
                .filter(t -> !"income".equals(t.type()))
                .toList();
        List<CategoryTotal> income = plan.categoryTotals().stream()
                .filter(t -> "income".equals(t.type()))
                .toList();

        int rowIndex = 1;
        Range expenseRange = writeBlock(sheet, expense, rowIndex, money);
        rowIndex = expenseRange.hasData() ? expenseRange.lastRow() + 2 : rowIndex + 1;
        Range incomeRange = writeBlock(sheet, income, rowIndex, money);

        sheet.setColumnWidth(0, 18 * 256);
        sheet.setColumnWidth(1, 14 * 256);
        sheet.setColumnWidth(2, 10 * 256);
        return new CategoryRanges(expenseRange, incomeRange);
    }

    private Range writeBlock(XSSFSheet sheet, List<CategoryTotal> totals, int startRow, CellStyle money) {
        if (totals.isEmpty()) {
            return new Range(startRow, startRow - 1);
        }
        int rowIndex = startRow;
        for (CategoryTotal total : totals) {
            XSSFRow row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(total.categoryName());
            moneyCell(row, 1, total.amount(), money);
            row.createCell(2).setCellValue("income".equals(total.type()) ? "收入" : "支出");
        }
        return new Range(startRow, rowIndex - 1);
    }

    /* ---------- 图表：与统计页同样的「分类 → 金额」柱状图 ---------- */

    private void drawBarChart(XSSFSheet target, XSSFSheet dataSheet, Range range, String title, int anchorCol) {
        if (!range.hasData()) {
            // 这个方向没有数据就不画空图（Excel 里空图比没有图更难看）
            return;
        }
        XSSFDrawing drawing = target.createDrawingPatriarch();
        XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, anchorCol, 1, anchorCol + 6, 18);
        XSSFChart chart = drawing.createChart(anchor);

        chart.setTitleText(title);
        chart.setTitleOverlay(false);
        XDDFChartLegend legend = chart.getOrAddLegend();
        legend.setPosition(LegendPosition.BOTTOM);
        legend.setOverlay(false);

        XDDFCategoryAxis bottomAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
        XDDFValueAxis leftAxis = chart.createValueAxis(AxisPosition.LEFT);
        leftAxis.setCrosses(AxisCrosses.AUTO_ZERO);

        XDDFDataSource<String> categories = XDDFDataSourcesFactory.fromStringCellRange(
                dataSheet, new CellRangeAddress(range.firstRow(), range.lastRow(), 0, 0));
        XDDFNumericalDataSource<Double> values = XDDFDataSourcesFactory.fromNumericCellRange(
                dataSheet, new CellRangeAddress(range.firstRow(), range.lastRow(), 1, 1));

        XDDFBarChartData data = (XDDFBarChartData) chart.createData(ChartTypes.BAR, bottomAxis, leftAxis);
        XDDFBarChartData.Series series = (XDDFBarChartData.Series) data.addSeries(categories, values);
        series.setTitle("金额（元）", null);
        // COL = 竖直柱子，和统计页的柱状图一致
        data.setBarDirection(BarDirection.COL);
        chart.plot(data);
    }

    /* ---------- 明细 ---------- */

    private void writeDetail(XSSFSheet sheet, ExportPlan plan, CellStyle head, CellStyle money, int headerRow) {
        XSSFRow header = sheet.createRow(headerRow);
        String[] titles = {"日期", "分类", "摘要", "收入", "支出", "结余"};
        for (int i = 0; i < titles.length; i++) {
            cell(header, i, titles[i], head);
        }

        int rowIndex = headerRow + 1;
        for (var row : plan.rows()) {
            XSSFRow excelRow = sheet.createRow(rowIndex++);
            excelRow.createCell(0).setCellValue(row.getTransDate().toString());
            excelRow.createCell(1).setCellValue(row.getCategoryName());
            excelRow.createCell(2).setCellValue(row.getNote() == null ? "" : row.getNote());
            boolean income = "income".equals(row.getType());
            if (income) {
                moneyCell(excelRow, 3, row.getAmount(), money);
            } else {
                moneyCell(excelRow, 4, row.getAmount(), money);
            }
            moneyCell(excelRow, 5, row.getBalanceAfter(), money);
        }

        XSSFRow total = sheet.createRow(rowIndex);
        cell(total, 0, "合计", head);
        moneyCell(total, 3, plan.totalIncome(), money);
        moneyCell(total, 4, plan.totalExpense(), money);
        moneyCell(total, 5, plan.closing(), money);

        sheet.setColumnWidth(2, 32 * 256);
        for (int i = 3; i <= 5; i++) {
            sheet.setColumnWidth(i, 14 * 256);
        }
    }

    /* ---------- 样式 ---------- */

    private static CellStyle headStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    private static CellStyle moneyStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        // 两位小数：和页面上的金额格式一致，Excel 里再算也不会出现浮点尾巴
        style.setDataFormat(workbook.createDataFormat().getFormat("0.00"));
        return style;
    }

    private static void cell(XSSFRow row, int col, String value) {
        row.createCell(col).setCellValue(value == null ? "" : value);
    }

    private static void cell(XSSFRow row, int col, String value, CellStyle style) {
        var cell = row.createCell(col);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private static void moneyCell(XSSFRow row, int col, BigDecimal value, CellStyle style) {
        var cell = row.createCell(col);
        cell.setCellValue(value == null ? 0 : value.doubleValue());
        cell.setCellStyle(style);
    }
}