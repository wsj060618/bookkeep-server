package com.bookkeep.service.export;

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
import org.apache.poi.xddf.usermodel.chart.XDDFChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory;
import org.apache.poi.xddf.usermodel.chart.XDDFNumericalDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFValueAxis;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFChart;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTBarChart;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTBarSer;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTDPt;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTPieSer;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTPlotArea;
import org.openxmlformats.schemas.drawingml.x2006.chart.STBarGrouping;
import org.openxmlformats.schemas.drawingml.x2006.main.CTSRgbColor;
import org.openxmlformats.schemas.drawingml.x2006.main.CTShapeProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTSolidColorFillProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;

/**
 * 流水导出成 xlsx（PLAN §10.3），版式对齐真实班费表：
 *
 * <pre>
 * B1:L1   班级账本 班费收支明细
 * B2 收入  D2 合计        ┌ 柱状图（收入/支出/结余） ┐  ┌ 环形图（收支占比） ┐
 * B4 支出  D4 合计
 * B6 结余  D6 合计
 * 第 8 行   序号 | 日期 | 摘要 | 收入 | 支出 | 班费结余 | 备注
 * 第 9 行起 明细
 * </pre>
 *
 * <p>刻意去掉了「数量 / 单价 / 人均数量」：系统只存金额，编造数量会误导看表的人。
 * <p>图表数据放在第二张隐藏 sheet「统计」里，只留一张可见表，和用户手里的表格观感一致。
 */
@Slf4j
@Service
public class TransactionExcelExportService {

    /** 明细表头所在行（0 基）：图表按表格列宽竖排在 D..G，表头在第 17 行 */
    private static final int HEADER_ROW = 16;
    private static final int FIRST_DATA_ROW = 17;

    // 从 A 列开始（贴左），表头与顶部汇总用同一组列，宽度自然对齐
    private static final int COL_SEQ = 0;        // A
    private static final int COL_DATE = 1;       // B
    private static final int COL_SUMMARY = 2;    // C
    private static final int COL_INCOME = 3;     // D
    private static final int COL_EXPENSE = 4;    // E
    private static final int COL_BALANCE = 5;    // F
    private static final int COL_NOTE = 6;       // G

    /** 与截图一致的三色：收入蓝 / 支出绿 / 结余灰 */
    private static final byte[] BLUE = {0x5B, (byte) 0x9B, (byte) 0xD5};
    private static final byte[] GREEN = {0x70, (byte) 0xAD, 0x47};
    private static final byte[] GRAY = {0x7F, 0x7F, 0x7F};

    public void write(ExportPlan plan, OutputStream out) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Styles styles = new Styles(workbook);
            XSSFSheet sheet = workbook.createSheet("收支明细");
            XSSFSheet statSheet = workbook.createSheet("统计");

            writeSummary(sheet, plan, styles);
            writeHeader(sheet, styles);
            writeRows(sheet, plan, styles);

            int statRows = writeChartData(statSheet, plan, styles);
            drawBarChart(sheet, statSheet, statRows);
            drawDoughnutChart(sheet, statSheet, statRows);

            // 只留一张可见表：数据源 sheet 藏起来，打开文件看到的就是那张班费表
            workbook.setSheetHidden(1, true);

            workbook.write(out);
            out.flush();
        }
        log.info("导出 xlsx：{}，明细 {} 条", plan.filename(), plan.rows().size());
    }

    /* ---------- 顶部汇总（带底色，和截图一致） ---------- */

    private void writeSummary(XSSFSheet sheet, ExportPlan plan, Styles styles) {
        XSSFRow title = sheet.createRow(0);
        cell(title, COL_SEQ, plan.title(), styles.title);
        for (int c = COL_SEQ + 1; c <= COL_NOTE; c++) {
            title.createCell(c).setCellStyle(styles.title);
        }
        sheet.addMergedRegion(new CellRangeAddress(0, 0, COL_SEQ, COL_NOTE));

        summaryRow(sheet, 1, "收入", plan.totalIncome(), styles);
        summaryRow(sheet, 3, "支出", plan.totalExpense(), styles);
        summaryRow(sheet, 5, "结余", plan.closing(), styles);

        sheet.setColumnWidth(COL_SEQ, 7 * 256);
        sheet.setColumnWidth(COL_DATE, 13 * 256);
        sheet.setColumnWidth(COL_SUMMARY, 30 * 256);
        sheet.setColumnWidth(COL_INCOME, 12 * 256);
        sheet.setColumnWidth(COL_EXPENSE, 12 * 256);
        sheet.setColumnWidth(COL_BALANCE, 13 * 256);
        sheet.setColumnWidth(COL_NOTE, 28 * 256);
    }

    /** 顶部三行：标签占 A:B（与表格前两列同宽），金额放 C，右边就是图表区 */
    private void summaryRow(XSSFSheet sheet, int rowIndex, String label, BigDecimal value, Styles styles) {
        XSSFRow row = sheet.createRow(rowIndex);
        cell(row, COL_SEQ, label, styles.summaryLabel);
        cell(row, COL_SEQ + 1, null, styles.summaryLabel);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, COL_SEQ, COL_SEQ + 1));
        moneyCell(row, COL_SUMMARY, value, styles.summaryMoney);
    }

    /* ---------- 单行表头 ---------- */

    private void writeHeader(XSSFSheet sheet, Styles styles) {
        XSSFRow header = sheet.createRow(HEADER_ROW);
        String[] titles = {"序号", "日期", "摘要", "收入", "支出", "班费结余", "备注"};
        for (int i = 0; i < titles.length; i++) {
            cell(header, COL_SEQ + i, titles[i], styles.head);
        }
    }

    /* ---------- 明细 ---------- */

    private void writeRows(XSSFSheet sheet, ExportPlan plan, Styles styles) {
        int rowIndex = FIRST_DATA_ROW;
        int seq = 1;
        for (var row : plan.rows()) {
            XSSFRow excel = sheet.createRow(rowIndex++);
            excel.createCell(COL_SEQ).setCellValue(seq++);
            dateCell(excel, COL_DATE, row.getTransDate(), styles);
            // 摘要放分类名，备注放流水备注（和纸质表的分工一致）
            excel.createCell(COL_SUMMARY).setCellValue(row.getCategoryName());
            boolean income = "income".equals(row.getType());
            if (income) {
                moneyCell(excel, COL_INCOME, row.getAmount(), styles.money);
            } else {
                moneyCell(excel, COL_EXPENSE, row.getAmount(), styles.money);
            }
            moneyCell(excel, COL_BALANCE, row.getBalanceAfter(), styles.money);
            excel.createCell(COL_NOTE).setCellValue(row.getNote() == null ? "" : row.getNote());
        }
    }

    /* ---------- 图表数据（隐藏 sheet） ---------- */

    private record StatRows(int firstRow, int lastRow) {
    }

    private int writeChartData(XSSFSheet sheet, ExportPlan plan, Styles styles) {
        XSSFRow header = sheet.createRow(0);
        cell(header, 0, "项目", styles.head);
        cell(header, 1, "金额", styles.head);

        row(sheet, 1, "收入", plan.totalIncome(), styles);
        row(sheet, 2, "支出", plan.totalExpense(), styles);
        row(sheet, 3, "结余", plan.closing(), styles);

        sheet.setColumnWidth(0, 12 * 256);
        sheet.setColumnWidth(1, 14 * 256);
        return 3;
    }

    private void row(XSSFSheet sheet, int index, String label, BigDecimal value, Styles styles) {
        XSSFRow row = sheet.createRow(index);
        row.createCell(0).setCellValue(label);
        moneyCell(row, 1, value, styles.money);
    }

    /* ---------- 柱状图：收入 / 支出 / 结余，一根一种颜色 ---------- */

    private void drawBarChart(XSSFSheet target, XSSFSheet dataSheet, int lastDataRow) {
        // 图表只占 D..G 四列（宽度和表格对齐），竖着排：柱状图在上、环形图在下
        XSSFDrawing drawing = target.createDrawingPatriarch();
        XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, COL_INCOME, 1, COL_NOTE + 1, 8);
        XSSFChart chart = drawing.createChart(anchor);
        chart.setTitleText("收支对比");
        chart.setTitleOverlay(false);
        chart.getOrAddLegend().setPosition(LegendPosition.BOTTOM);
        chart.getOrAddLegend().setOverlay(false);

        XDDFCategoryAxis bottomAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
        XDDFValueAxis leftAxis = chart.createValueAxis(AxisPosition.LEFT);
        leftAxis.setCrosses(AxisCrosses.AUTO_ZERO);

        XDDFDataSource<String> cats = XDDFDataSourcesFactory.fromStringCellRange(
                dataSheet, new CellRangeAddress(1, lastDataRow, 0, 0));
        XDDFNumericalDataSource<Double> vals = XDDFDataSourcesFactory.fromNumericCellRange(
                dataSheet, new CellRangeAddress(1, lastDataRow, 1, 1));

        XDDFBarChartData data = (XDDFBarChartData) chart.createData(ChartTypes.BAR, bottomAxis, leftAxis);
        XDDFBarChartData.Series series = (XDDFBarChartData.Series) data.addSeries(cats, vals);
        // 序列必须有名字：留空的 <c:tx/> 会让 Excel 判定序列无效，只画坐标轴不画柱子
        series.setTitle("金额", null);
        data.setBarDirection(BarDirection.COL);
        data.setVaryColors(true); // 每个点一种颜色（下面再精确指定）
        chart.plot(data);

        ensureClusteredGrouping(chart);
        paintPoints(chart, new byte[][]{BLUE, GREEN, GRAY});
    }

    /* ---------- 环形图：收入 vs 支出 占比 ---------- */

    private void drawDoughnutChart(XSSFSheet target, XSSFSheet dataSheet, int lastDataRow) {
        XSSFDrawing drawing = target.createDrawingPatriarch();
        XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, COL_INCOME, 8, COL_NOTE + 1, 16);
        XSSFChart chart = drawing.createChart(anchor);
        chart.setTitleText("收支占比");
        chart.setTitleOverlay(false);
        chart.getOrAddLegend().setPosition(LegendPosition.BOTTOM);
        chart.getOrAddLegend().setOverlay(false);

        XDDFDataSource<String> cats = XDDFDataSourcesFactory.fromStringCellRange(
                dataSheet, new CellRangeAddress(1, 2, 0, 0));   // 只要 收入 / 支出
        XDDFNumericalDataSource<Double> vals = XDDFDataSourcesFactory.fromNumericCellRange(
                dataSheet, new CellRangeAddress(1, 2, 1, 1));

        XDDFChartData data = chart.createData(ChartTypes.DOUGHNUT, null, null);
        XDDFChartData.Series series = data.addSeries(cats, vals);
        series.setTitle("金额", null); // 同上：空 <c:tx/> 会让序列失效
        data.setVaryColors(true);
        chart.plot(data);

        paintPiePoints(chart, new byte[][]{BLUE, GREEN});
    }

    /** 补上 c:grouping（POI 的 XDDF 工厂不写，缺了 Excel 可能整张图不渲染） */
    private void ensureClusteredGrouping(XSSFChart chart) {
        try {
            CTBarChart bar = chart.getCTChart().getPlotArea().getBarChartArray(0);
            if (!bar.isSetGrouping()) {
                bar.addNewGrouping().setVal(STBarGrouping.CLUSTERED);
            }
        } catch (RuntimeException e) {
            log.debug("补充柱状图 grouping 失败：{}", e.getMessage());
        }
    }

    /** 给柱状图的每个数据点单独上色；形状 API 因 POI 版本而异，失败就退回默认配色 */
    private void paintPoints(XSSFChart chart, byte[][] colors) {
        try {
            CTPlotArea plotArea = chart.getCTChart().getPlotArea();
            CTBarSer series = plotArea.getBarChartArray(0).getSerArray(0);
            for (int i = 0; i < colors.length; i++) {
                CTDPt point = series.addNewDPt();
                point.addNewIdx().setVal(i);
                applyFill(point.addNewSpPr(), colors[i]);
            }
        } catch (RuntimeException e) {
            log.debug("柱状图逐点上色失败，使用默认配色：{}", e.getMessage());
        }
    }

    private void paintPiePoints(XSSFChart chart, byte[][] colors) {
        try {
            CTPlotArea plotArea = chart.getCTChart().getPlotArea();
            CTPieSer series = plotArea.getDoughnutChartArray(0).getSerArray(0);
            for (int i = 0; i < colors.length; i++) {
                CTDPt point = series.addNewDPt();
                point.addNewIdx().setVal(i);
                applyFill(point.addNewSpPr(), colors[i]);
            }
        } catch (RuntimeException e) {
            log.debug("环形图逐点上色失败，使用默认配色：{}", e.getMessage());
        }
    }

    private void applyFill(CTShapeProperties shapeProperties, byte[] rgb) {
        CTSolidColorFillProperties fill = shapeProperties.addNewSolidFill();
        CTSRgbColor color = fill.addNewSrgbClr();
        color.setVal(rgb);
    }

    /* ---------- 样式 ---------- */

    /** 一套样式在同一个 workbook 里复用：CellStyle 数量有上限，逐格新建会爆 */
    private static final class Styles {
        private final CellStyle title;
        private final CellStyle head;
        private final CellStyle summaryLabel;
        private final CellStyle summaryMoney;
        private final CellStyle money;
        private final CellStyle date;

        private Styles(XSSFWorkbook workbook) {
            title = workbook.createCellStyle();
            XSSFFont titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeight((short) 16);
            title.setFont(titleFont);
            title.setAlignment(HorizontalAlignment.CENTER);
            title.setVerticalAlignment(VerticalAlignment.CENTER);

            head = workbook.createCellStyle();
            XSSFFont headFont = workbook.createFont();
            headFont.setBold(true);
            headFont.setColor(IndexedColors.WHITE.getIndex());
            head.setFont(headFont);
            head.setAlignment(HorizontalAlignment.CENTER);
            head.setVerticalAlignment(VerticalAlignment.CENTER);
            head.setFillForegroundColor(new XSSFColor(BLUE, null));
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setBorderBottom(BorderStyle.THIN);
            head.setBorderTop(BorderStyle.THIN);
            head.setBorderLeft(BorderStyle.THIN);
            head.setBorderRight(BorderStyle.THIN);

            summaryLabel = workbook.createCellStyle();
            XSSFFont whiteBold = workbook.createFont();
            whiteBold.setBold(true);
            whiteBold.setColor(IndexedColors.WHITE.getIndex());
            summaryLabel.setFont(whiteBold);
            summaryLabel.setAlignment(HorizontalAlignment.CENTER);
            summaryLabel.setVerticalAlignment(VerticalAlignment.CENTER);
            summaryLabel.setFillForegroundColor(new XSSFColor(BLUE, null));
            summaryLabel.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            summaryMoney = workbook.createCellStyle();
            summaryMoney.setDataFormat(workbook.createDataFormat().getFormat("¥#,##0.00"));
            summaryMoney.setAlignment(HorizontalAlignment.RIGHT);
            summaryMoney.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            summaryMoney.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            summaryMoney.setBorderLeft(BorderStyle.THIN);
            summaryMoney.setBorderRight(BorderStyle.THIN);
            summaryMoney.setBorderTop(BorderStyle.THIN);
            summaryMoney.setBorderBottom(BorderStyle.THIN);

            money = workbook.createCellStyle();
            money.setDataFormat(workbook.createDataFormat().getFormat("¥#,##0.00"));

            date = workbook.createCellStyle();
            date.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
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