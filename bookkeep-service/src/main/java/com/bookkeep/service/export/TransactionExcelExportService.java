package com.bookkeep.service.export;

import com.bookkeep.service.export.TransactionExportService.CategoryTotal;
import com.bookkeep.service.export.TransactionExportService.ExportPlan;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.VerticalAlignment;
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
 * 流水导出成 xlsx（PLAN §10.3），版式对齐真实班费表：
 *
 * <pre>
 * B1:L1              班级账本 班费收支明细表（合并居中）
 * B2 收入  D2 合计      （右侧放两张分类图）
 * B4 支出  D4 合计
 * B6 结余  D6 合计
 * 第 8-9 行 两行表头：
 *   序号 | 日期 | 摘要 | 收入(数量|人均数量|总价) | 支出(数量|单价|金额) | 结余 | 备注
 * 第 10 行起 明细
 * </pre>
 *
 * <p>数量 / 单价 / 人均数量 属于「纸质表上的算账过程」，系统里只存金额，所以只填总价与金额，
 * 其余留空——强行编造数量反而会误导看表的人。
 * <p>数据来自 {@link TransactionExportService#prepare}，与 CSV 导出同一份口径。
 */
@Slf4j
@Service
public class TransactionExcelExportService {

    /** 明细表头所在行（0 基）：第 8、9 行是两行表头 */
    private static final int HEADER_ROW = 7;
    private static final int FIRST_DATA_ROW = 9;

    /** 列下标（0 基）：B=1 序号、C=2 日期、D=3 摘要、E..H 收入、I..K 支出、L=11 结余、M=12 备注 */
    private static final int COL_SEQ = 1;
    private static final int COL_DATE = 2;
    private static final int COL_SUMMARY = 3;
    private static final int COL_INCOME_QTY = 4;
    private static final int COL_INCOME_AVG = 5;
    private static final int COL_INCOME_TOTAL = 6;
    private static final int COL_EXPENSE_QTY = 7;
    private static final int COL_EXPENSE_PRICE = 8;
    private static final int COL_EXPENSE_AMOUNT = 9;
    private static final int COL_BALANCE = 10;
    private static final int COL_NOTE = 11;

    public void write(ExportPlan plan, OutputStream out) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Styles styles = new Styles(workbook);
            XSSFSheet sheet = workbook.createSheet("收支明细");
            writeSummary(sheet, plan, styles);
            writeHeader(sheet, styles);
            writeRows(sheet, plan, styles);

            XSSFSheet chartSheet = workbook.createSheet("分类合计");
            CategoryRanges ranges = writeCategoryTotals(chartSheet, plan, styles);
            drawBarChart(sheet, chartSheet, ranges.expense(), "支出分类", 13, 1);
            drawBarChart(sheet, chartSheet, ranges.income(), "收入分类", 13, 20);

            workbook.write(out);
            out.flush();
        }
        log.info("导出 xlsx：{}，明细 {} 条", plan.filename(), plan.rows().size());
    }

    /* ---------- 顶部汇总：收入 / 支出 / 结余 ---------- */

    private void writeSummary(XSSFSheet sheet, ExportPlan plan, Styles styles) {
        XSSFRow title = sheet.createRow(0);
        cell(title, COL_SEQ, plan.title(), styles.title);
        // 合并区里其余单元格只上样式：createCell 会**替换**已有单元格，不能从 COL_SEQ 重新 create
        for (int c = COL_SEQ + 1; c <= COL_NOTE; c++) {
            title.createCell(c).setCellStyle(styles.title);
        }
        sheet.addMergedRegion(new CellRangeAddress(0, 0, COL_SEQ, COL_NOTE));

        summaryRow(sheet, 1, "收入", plan.totalIncome(), styles);
        summaryRow(sheet, 3, "支出", plan.totalExpense(), styles);
        summaryRow(sheet, 5, "结余", plan.closing(), styles);

        // 右上角补一行导出信息，打印/发群时能看出是哪一段数据
        XSSFRow meta = sheet.getRow(5);
        cell(meta, COL_BALANCE, "统计区间：" + plan.rangeText() + "　导出时间：" + plan.generatedAt(), styles.hint);
        sheet.addMergedRegion(new CellRangeAddress(5, 5, COL_BALANCE, COL_NOTE));

        sheet.setColumnWidth(COL_SEQ, 6 * 256);
        sheet.setColumnWidth(COL_DATE, 12 * 256);
        sheet.setColumnWidth(COL_SUMMARY, 30 * 256);
        for (int c = COL_INCOME_QTY; c <= COL_EXPENSE_AMOUNT; c++) {
            sheet.setColumnWidth(c, 10 * 256);
        }
        sheet.setColumnWidth(COL_BALANCE, 11 * 256);
        sheet.setColumnWidth(COL_NOTE, 28 * 256);
    }

    private void summaryRow(XSSFSheet sheet, int rowIndex, String label, BigDecimal value, Styles styles) {
        XSSFRow row = sheet.createRow(rowIndex);
        cell(row, COL_SEQ, label, styles.bold);
        cell(row, COL_SEQ + 1, null, styles.bold);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, COL_SEQ, COL_SEQ + 1));
        moneyCell(row, COL_DATE + 1, value, styles.money);
    }

    /* ---------- 两行表头 ---------- */

    private void writeHeader(XSSFSheet sheet, Styles styles) {
        XSSFRow top = sheet.createRow(HEADER_ROW);
        XSSFRow bottom = sheet.createRow(HEADER_ROW + 1);

        verticalPair(sheet, COL_SEQ, "序号", top, bottom, styles);
        verticalPair(sheet, COL_DATE, "日期", top, bottom, styles);
        verticalPair(sheet, COL_SUMMARY, "摘要", top, bottom, styles);

        groupHeader(sheet, top, COL_INCOME_QTY, COL_INCOME_TOTAL, "收入", styles);
        cell(bottom, COL_INCOME_QTY, "数量", styles.head);
        cell(bottom, COL_INCOME_AVG, "人均数量", styles.head);
        cell(bottom, COL_INCOME_TOTAL, "总价", styles.head);

        groupHeader(sheet, top, COL_EXPENSE_QTY, COL_EXPENSE_AMOUNT, "支出", styles);
        cell(bottom, COL_EXPENSE_QTY, "数量", styles.head);
        cell(bottom, COL_EXPENSE_PRICE, "单价", styles.head);
        cell(bottom, COL_EXPENSE_AMOUNT, "金额", styles.head);

        verticalPair(sheet, COL_BALANCE, "结余", top, bottom, styles);
        verticalPair(sheet, COL_NOTE, "备注", top, bottom, styles);
    }

    /** 跨两行合并的单列表头（序号 / 日期 / 摘要 …） */
    private void verticalPair(XSSFSheet sheet, int col, String text, XSSFRow top, XSSFRow bottom, Styles styles) {
        cell(top, col, text, styles.head);
        cell(bottom, col, null, styles.head);
        sheet.addMergedRegion(new CellRangeAddress(HEADER_ROW, HEADER_ROW + 1, col, col));
    }

    /** 横向合并的分组表头（收入 / 支出） */
    private void groupHeader(XSSFSheet sheet, XSSFRow top, int from, int to, String text, Styles styles) {
        cell(top, from, text, styles.head);
        // from 那格已经有文字，从 from+1 开始补样式（createCell 会替换已有单元格）
        for (int c = from + 1; c <= to; c++) {
            top.createCell(c).setCellStyle(styles.head);
        }
        sheet.addMergedRegion(new CellRangeAddress(HEADER_ROW, HEADER_ROW, from, to));
    }

    /* ---------- 明细 ---------- */

    private void writeRows(XSSFSheet sheet, ExportPlan plan, Styles styles) {
        int rowIndex = FIRST_DATA_ROW;
        int seq = 1;
        for (var row : plan.rows()) {
            XSSFRow excel = sheet.createRow(rowIndex++);
            excel.createCell(COL_SEQ).setCellValue(seq++);
            dateCell(excel, COL_DATE, row.getTransDate(), styles);
            // 摘要放分类名（和纸质表一致），补充说明进备注列
            excel.createCell(COL_SUMMARY).setCellValue(row.getCategoryName());
            boolean income = "income".equals(row.getType());
            if (income) {
                moneyCell(excel, COL_INCOME_TOTAL, row.getAmount(), styles.money);
            } else {
                moneyCell(excel, COL_EXPENSE_AMOUNT, row.getAmount(), styles.money);
            }
            moneyCell(excel, COL_BALANCE, row.getBalanceAfter(), styles.money);
            excel.createCell(COL_NOTE).setCellValue(row.getNote() == null ? "" : row.getNote());
        }
    }

    /* ---------- 分类合计（图表数据源） ---------- */

    private record Range(int firstRow, int lastRow) {
        boolean hasData() {
            return lastRow >= firstRow;
        }
    }

    private record CategoryRanges(Range expense, Range income) {
    }

    private CategoryRanges writeCategoryTotals(XSSFSheet sheet, ExportPlan plan, Styles styles) {
        XSSFRow header = sheet.createRow(0);
        cell(header, 0, "分类", styles.head);
        cell(header, 1, "金额", styles.head);
        cell(header, 2, "类型", styles.head);

        List<CategoryTotal> expense = plan.categoryTotals().stream()
                .filter(t -> !"income".equals(t.type()))
                .toList();
        List<CategoryTotal> income = plan.categoryTotals().stream()
                .filter(t -> "income".equals(t.type()))
                .toList();

        Range expenseRange = writeBlock(sheet, expense, 1, styles);
        Range incomeRange = writeBlock(sheet, income, expenseRange.hasData() ? expenseRange.lastRow() + 2 : 2, styles);

        sheet.setColumnWidth(0, 18 * 256);
        sheet.setColumnWidth(1, 14 * 256);
        sheet.setColumnWidth(2, 10 * 256);
        return new CategoryRanges(expenseRange, incomeRange);
    }

    private Range writeBlock(XSSFSheet sheet, List<CategoryTotal> totals, int startRow, Styles styles) {
        if (totals.isEmpty()) {
            return new Range(startRow, startRow - 1);
        }
        int rowIndex = startRow;
        for (CategoryTotal total : totals) {
            XSSFRow row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(total.categoryName());
            moneyCell(row, 1, total.amount(), styles.money);
            row.createCell(2).setCellValue("income".equals(total.type()) ? "收入" : "支出");
        }
        return new Range(startRow, rowIndex - 1);
    }

    /* ---------- 图表（放在表格右侧，不压数据） ---------- */

    private void drawBarChart(XSSFSheet target, XSSFSheet dataSheet, Range range,
                              String title, int anchorCol, int anchorRow) {
        if (!range.hasData()) {
            return;
        }
        XSSFDrawing drawing = target.createDrawingPatriarch();
        XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, anchorCol, anchorRow, anchorCol + 7, anchorRow + 18);
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
        data.setBarDirection(BarDirection.COL);
        chart.plot(data);
    }

    /* ---------- 样式与单元格 ---------- */

    /** 一套样式在同一个 workbook 里复用：POI 的 CellStyle 数量有上限，逐格新建会爆 */
    private static final class Styles {
        private final CellStyle title;
        private final CellStyle head;
        private final CellStyle bold;
        private final CellStyle money;
        private final CellStyle date;
        private final CellStyle hint;

        private Styles(XSSFWorkbook workbook) {
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 16);
            title = workbook.createCellStyle();
            title.setFont(titleFont);
            title.setAlignment(HorizontalAlignment.CENTER);
            title.setVerticalAlignment(VerticalAlignment.CENTER);

            Font boldFont = workbook.createFont();
            boldFont.setBold(true);
            bold = workbook.createCellStyle();
            bold.setFont(boldFont);

            head = workbook.createCellStyle();
            head.setFont(boldFont);
            head.setAlignment(HorizontalAlignment.CENTER);
            head.setVerticalAlignment(VerticalAlignment.CENTER);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setBorderBottom(BorderStyle.THIN);
            head.setBorderTop(BorderStyle.THIN);
            head.setBorderLeft(BorderStyle.THIN);
            head.setBorderRight(BorderStyle.THIN);

            money = workbook.createCellStyle();
            money.setDataFormat(workbook.createDataFormat().getFormat("0.00"));

            date = workbook.createCellStyle();
            date.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));

            hint = workbook.createCellStyle();
            Font hintFont = workbook.createFont();
            hintFont.setFontHeightInPoints((short) 9);
            hintFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
            hint.setFont(hintFont);
            hint.setAlignment(HorizontalAlignment.RIGHT);
        }
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

    private static void dateCell(XSSFRow row, int col, java.time.LocalDate value, Styles styles) {
        var cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(styles.date);
    }
}