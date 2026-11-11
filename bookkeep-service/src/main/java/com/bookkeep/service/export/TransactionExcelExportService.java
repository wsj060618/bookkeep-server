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
import org.apache.poi.xddf.usermodel.chart.XDDFChartLegend;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory;
import org.apache.poi.xddf.usermodel.chart.XDDFNumericalDataSource;
import org.apache.poi.xddf.usermodel.chart.XDDFValueAxis;
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
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextParagraph;
import org.openxmlformats.schemas.drawingml.x2006.main.CTSRgbColor;
import org.openxmlformats.schemas.drawingml.x2006.main.CTShapeProperties;
import org.openxmlformats.schemas.drawingml.x2006.main.CTSolidColorFillProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;

/**
 * 流水导出 xlsx（版式：汇总在上、图表在右、明细从第 14 行起）。
 * <p>图表数据放在隐藏的 J/K 列；隐藏 sheet 会让 Excel 丢掉整条序列。
 */
@Slf4j
@Service
public class TransactionExcelExportService {

    /** 表头行（0 基） */
    private static final int HEADER_ROW = 12;
    private static final int FIRST_DATA_ROW = 13;

    /** 表格列（0 基）：A..G */
    private static final int COL_SEQ = 0;
    private static final int COL_DATE = 1;
    private static final int COL_SUMMARY = 2;
    private static final int COL_INCOME = 3;
    private static final int COL_EXPENSE = 4;
    private static final int COL_BALANCE = 5;
    private static final int COL_NOTE = 6;

    /** 表格列宽（字符数）：A 序号 / B 日期 / C 摘要 / D 收入 / E 支出 / F 班费结余 / G 备注（备注按内容自适应） */
    private static final int[] COL_WIDTHS = {7, 14, 30, 16, 16, 16, 34};

    /** 备注列最宽 255（Excel 列宽上限），再长就让它去，宁可宽也不截断 */
    private static final int NOTE_MAX_WIDTH = 255;

    /** 图表数据区：J/K 两列，放完就隐藏，不污染表面 */
    private static final int COL_DATA_LABEL = 9;
    private static final int COL_DATA_VALUE = 10;

    /**
     * 与网页端同一套语义色：收入绿 / 支出红 / 结余蓝。
     * <p>以前这里是 Excel 参考表的「蓝/绿/灰」，后来网页端统一改成绿/红/蓝，
     * 导出表跟着改，免得同一笔钱在屏幕和 Excel 里是两个颜色。
     */
    private static final byte[] INCOME = {0x10, (byte) 0xB9, (byte) 0x81};
    private static final byte[] EXPENSE = {(byte) 0xEF, 0x44, 0x44};
    private static final byte[] BALANCE = {0x40, (byte) 0x9E, (byte) 0xFF};
    /** 表头与汇总标签的底色：用 Element 主色蓝，和网页按钮同色系 */
    private static final byte[] HEADER = {0x40, (byte) 0x9E, (byte) 0xFF};

    public void write(ExportPlan plan, OutputStream out) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Styles styles = new Styles(workbook);
            XSSFSheet sheet = workbook.createSheet("收支明细");
            // 备注列按最长备注定宽：否则长备注会溢出到表格外面（G 列右边没有内容，Excel 就让字飘出去）
            int[] widths = widthsFor(plan);

            writeSummary(sheet, plan, styles, widths);
            writeChartData(sheet, plan);
            drawBarChart(sheet, widths);
            drawDoughnutChart(sheet, widths);
            writeHeader(sheet, styles);
            writeRows(sheet, plan, styles);

            workbook.write(out);
            out.flush();
        }
        log.info("导出 xlsx：{}，明细 {} 条", plan.filename(), plan.rows().size());
    }

    /** 列宽基线 + 备注列自适应（中文/全角算 2 个字符宽，否则列宽永远对不上） */
    private int[] widthsFor(ExportPlan plan) {
        int[] widths = COL_WIDTHS.clone();
        int longest = 0;
        for (var row : plan.rows()) {
            longest = Math.max(longest, displayWidth(row.getNote()));
        }
        // +2 是两侧留白；列宽上限 255 是 Excel 自己的硬限制
        widths[COL_NOTE] = Math.max(COL_WIDTHS[COL_NOTE], Math.min(longest + 2, NOTE_MAX_WIDTH));
        return widths;
    }

    private static int displayWidth(String text) {
        if (text == null) {
            return 0;
        }
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += text.charAt(i) <= 0x7F ? 1 : 2;
        }
        return width;
    }

    /* ---------- 顶部汇总（A..C，带底色） ---------- */

    private void writeSummary(XSSFSheet sheet, ExportPlan plan, Styles styles, int[] widths) {
        XSSFRow title = sheet.createRow(0);
        title.setHeightInPoints(26); // 14 磅标题要留够行高，否则被裁成一条线
        cell(title, COL_SEQ, plan.title(), styles.title);
        for (int c = COL_SEQ + 1; c <= COL_NOTE; c++) {
            title.createCell(c).setCellStyle(styles.title);
        }
        sheet.addMergedRegion(new CellRangeAddress(0, 0, COL_SEQ, COL_NOTE));

        summaryRow(sheet, 1, "收入", plan.totalIncome(), styles);
        summaryRow(sheet, 3, "支出", plan.totalExpense(), styles);
        summaryRow(sheet, 5, "结余", plan.closing(), styles);

        // 表格拉宽：图表并排一行要靠这些列宽撑出足够空间
        for (int c = 0; c < widths.length; c++) {
            sheet.setColumnWidth(c, widths[c] * 256);
        }

        // 图表数据列用完就藏起来，别让表面多出两列数字
        sheet.setColumnWidth(COL_DATA_LABEL, 10 * 256);
        sheet.setColumnWidth(COL_DATA_VALUE, 12 * 256);
        sheet.setColumnHidden(COL_DATA_LABEL, true);
        sheet.setColumnHidden(COL_DATA_VALUE, true);
    }

    /** 顶部三行：标签占 A:B（与表格前两列同宽），金额放 C */
    private void summaryRow(XSSFSheet sheet, int rowIndex, String label, BigDecimal value, Styles styles) {
        XSSFRow row = sheet.createRow(rowIndex);
        cell(row, COL_SEQ, label, styles.summaryLabel);
        cell(row, COL_SEQ + 1, null, styles.summaryLabel);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, COL_SEQ, COL_SEQ + 1));
        moneyCell(row, COL_SUMMARY, value, styles.summaryMoney);
    }

    /* ---------- 图表数据（同表隐藏列） ---------- */

    private void writeChartData(XSSFSheet sheet, ExportPlan plan) {
        row(sheet, 1, "收入", plan.totalIncome());
        row(sheet, 2, "支出", plan.totalExpense());
        row(sheet, 3, "结余", plan.closing());
    }

    private void row(XSSFSheet sheet, int index, String label, BigDecimal value) {
        XSSFRow row = sheet.getRow(index) != null ? sheet.getRow(index) : sheet.createRow(index);
        var labelCell = row.createCell(COL_DATA_LABEL);
        labelCell.setCellValue(label);
        var valueCell = row.createCell(COL_DATA_VALUE);
        valueCell.setCellValue(value == null ? 0 : value.doubleValue());
    }

    /* ---------- 柱状图：收入 / 支出 / 结余（竖着的三根柱子） ---------- */

    private void drawBarChart(XSSFSheet sheet, int[] widths) {
        XSSFDrawing drawing = sheet.createDrawingPatriarch();
        // 两张图并排一行、各占 D..G 的一半宽度（按 EMU 均分，不受列边界限制）
        long block = blockEmu(widths);
        int[] from = posIn(widths, 0);
        int[] mid = posIn(widths, block / 2);
        XSSFClientAnchor anchor = new XSSFClientAnchor(0, 0, mid[1], 0,
                from[0], 1, mid[0], HEADER_ROW - 1);
        XSSFChart chart = drawing.createChart(anchor);
        // 图表数据在隐藏的 J/K 列；plotVisOnly=true（POI 默认）会让 Excel 丢掉隐藏单元格，
        // 序列直接消失、只剩坐标轴，所以必须显式关掉
        chart.setPlotOnlyVisibleCells(false);
        chart.setTitleText("收支对比");
        chart.setTitleOverlay(false);
        chart.getOrAddLegend().setPosition(LegendPosition.BOTTOM);
        chart.getOrAddLegend().setOverlay(false);

        XDDFCategoryAxis bottomAxis = chart.createCategoryAxis(AxisPosition.BOTTOM);
        bottomAxis.setCrosses(AxisCrosses.AUTO_ZERO);
        XDDFValueAxis leftAxis = chart.createValueAxis(AxisPosition.LEFT);
        leftAxis.setCrosses(AxisCrosses.AUTO_ZERO);

        XDDFDataSource<String> cats = XDDFDataSourcesFactory.fromStringCellRange(
                sheet, new CellRangeAddress(1, 3, COL_DATA_LABEL, COL_DATA_LABEL));
        XDDFNumericalDataSource<Double> vals = XDDFDataSourcesFactory.fromNumericCellRange(
                sheet, new CellRangeAddress(1, 3, COL_DATA_VALUE, COL_DATA_VALUE));

        XDDFBarChartData data = (XDDFBarChartData) chart.createData(ChartTypes.BAR, bottomAxis, leftAxis);
        XDDFBarChartData.Series series = (XDDFBarChartData.Series) data.addSeries(cats, vals);
        // 序列必须有名字：空 <c:tx/> 会让 Excel 判定序列无效（只画坐标轴、柱子全空）
        series.setTitle("金额", null);
        // 竖直柱子（col），每根柱子一种颜色
        data.setBarDirection(BarDirection.COL);
        data.setVaryColors(true);
        chart.plot(data);

        ensureClusteredGrouping(chart);
        shrinkTitle(chart, "收支对比");
        paintPoints(chart, new byte[][]{INCOME, EXPENSE, BALANCE});
    }

    /* ---------- 环形图：收入 vs 支出 ---------- */

    private void drawDoughnutChart(XSSFSheet sheet, int[] widths) {
        XSSFDrawing drawing = sheet.createDrawingPatriarch();
        long block = blockEmu(widths);
        int[] mid = posIn(widths, block / 2);
        int[] to = posIn(widths, block);
        XSSFClientAnchor anchor = new XSSFClientAnchor(mid[1], 0, 0, 0,
                mid[0], 1, to[0], HEADER_ROW - 1);
        XSSFChart chart = drawing.createChart(anchor);
        chart.setPlotOnlyVisibleCells(false); // 同上：数据在隐藏列
        chart.setTitleText("收支占比");
        chart.setTitleOverlay(false);
        chart.getOrAddLegend().setPosition(LegendPosition.BOTTOM);
        chart.getOrAddLegend().setOverlay(false);

        XDDFDataSource<String> cats = XDDFDataSourcesFactory.fromStringCellRange(
                sheet, new CellRangeAddress(1, 2, COL_DATA_LABEL, COL_DATA_LABEL));  // 只要 收入 / 支出
        XDDFNumericalDataSource<Double> vals = XDDFDataSourcesFactory.fromNumericCellRange(
                sheet, new CellRangeAddress(1, 2, COL_DATA_VALUE, COL_DATA_VALUE));

        XDDFChartData data = chart.createData(ChartTypes.DOUGHNUT, null, null);
        XDDFChartData.Series series = data.addSeries(cats, vals);
        series.setTitle("金额", null);
        data.setVaryColors(true);
        chart.plot(data);

        shrinkTitle(chart, "收支占比");
        ensureHoleSize(chart);
        paintPiePoints(chart, new byte[][]{INCOME, EXPENSE});
    }

    /**
     * D..G 四列的总宽（EMU），图表按它均分两半。列宽→像素用的是 POI 的 7px/字符 + 5px 边距
     */
    private static final long EMU_PER_PIXEL = 9525L;

    private static long blockEmu(int[] widths) {
        long total = 0;
        for (int c = COL_INCOME; c <= COL_NOTE; c++) {
            total += (widths[c] * 7L + 5) * EMU_PER_PIXEL;
        }
        return total;
    }

    /** 把「D 列起算的偏移（EMU）」换算成 (列号, 列内偏移)，锚点才能落在列中间 */
    private static int[] posIn(int[] widths, long offsetEmu) {
        long acc = 0;
        for (int c = COL_INCOME; c <= COL_NOTE; c++) {
            long width = (widths[c] * 7L + 5) * EMU_PER_PIXEL;
            if (offsetEmu < acc + width) {
                return new int[]{c, (int) (offsetEmu - acc)};
            }
            acc += width;
        }
        return new int[]{COL_NOTE + 1, 0};
    }

    /** 环形图默认中间没有洞（POI 不写 holeSize），补 50% 才是真正的环形 */
    private void ensureHoleSize(XSSFChart chart) {
        try {
            var doughnut = chart.getCTChart().getPlotArea().getDoughnutChartArray(0);
            if (!doughnut.isSetHoleSize()) {
                doughnut.addNewHoleSize().setVal((short) 50);
            }
        } catch (RuntimeException e) {
            log.debug("设置环形图内径失败：{}", e.getMessage());
        }
    }

    /** 补上 c:grouping（POI 的 XDDF 工厂不写，缺了 Excel 可能整图不渲染） */
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

    /** 标题默认 18pt，会把矮图表的绘图区挤没：重建标题段落，pPr（11pt）必须排在文字前 */
    private void shrinkTitle(XSSFChart chart, String text) {
        try {
            var title = chart.getCTChart().getTitle();
            if (title == null || title.getTx() == null || title.getTx().getRich() == null) {
                return;
            }
            var rich = title.getTx().getRich();
            rich.setPArray(new CTTextParagraph[0]);
            var paragraph = rich.addNewP();
            paragraph.addNewPPr().addNewDefRPr().setSz(1100);
            paragraph.addNewR().setT(text);
        } catch (RuntimeException e) {
            log.debug("缩小图表标题字号失败：{}", e.getMessage());
        }
    }

    /** 给柱状图的每个数据点单独上色 */
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
            var seqCell = excel.createCell(COL_SEQ);
            seqCell.setCellValue(seq++);
            seqCell.setCellStyle(styles.seq);
            dateCell(excel, COL_DATE, row.getTransDate(), styles);
            // 摘要放分类名，备注放流水备注（和纸质表的分工一致）
            cell(excel, COL_SUMMARY, row.getCategoryName(), styles.text);
            boolean income = "income".equals(row.getType());
            // 空着的那一侧也要建单元格：没有 cell 就没有边框，那一列会缺一排横线
            if (income) {
                moneyCell(excel, COL_INCOME, row.getAmount(), styles.money);
                blankCell(excel, COL_EXPENSE, styles.money);
            } else {
                blankCell(excel, COL_INCOME, styles.money);
                moneyCell(excel, COL_EXPENSE, row.getAmount(), styles.money);
            }
            moneyCell(excel, COL_BALANCE, row.getBalanceAfter(), styles.money);
            cell(excel, COL_NOTE, row.getNote(), styles.text);
        }
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
        private final CellStyle text;
        private final CellStyle seq;

        private Styles(XSSFWorkbook workbook) {
            title = workbook.createCellStyle();
            XSSFFont titleFont = workbook.createFont();
            titleFont.setBold(true);
            // 注意：setFontHeight 的单位是 1/20 磅，写成 16 会变成 0.8 磅（标题看起来像一排点）
            titleFont.setFontHeightInPoints((short) 14);
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
            head.setFillForegroundColor(new XSSFColor(HEADER, null));
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            thinBorder(head);

            summaryLabel = workbook.createCellStyle();
            XSSFFont whiteBold = workbook.createFont();
            whiteBold.setBold(true);
            whiteBold.setColor(IndexedColors.WHITE.getIndex());
            summaryLabel.setFont(whiteBold);
            summaryLabel.setAlignment(HorizontalAlignment.CENTER);
            summaryLabel.setVerticalAlignment(VerticalAlignment.CENTER);
            summaryLabel.setFillForegroundColor(new XSSFColor(HEADER, null));
            summaryLabel.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            thinBorder(summaryLabel);

            summaryMoney = workbook.createCellStyle();
            summaryMoney.setDataFormat(workbook.createDataFormat().getFormat("¥#,##0.00"));
            summaryMoney.setAlignment(HorizontalAlignment.RIGHT);
            summaryMoney.setVerticalAlignment(VerticalAlignment.CENTER);
            summaryMoney.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            summaryMoney.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            thinBorder(summaryMoney);

            money = workbook.createCellStyle();
            money.setDataFormat(workbook.createDataFormat().getFormat("¥#,##0.00"));
            money.setAlignment(HorizontalAlignment.RIGHT);
            money.setVerticalAlignment(VerticalAlignment.CENTER);
            thinBorder(money);

            date = workbook.createCellStyle();
            date.setDataFormat(workbook.createDataFormat().getFormat("yyyy-mm-dd"));
            date.setAlignment(HorizontalAlignment.CENTER);
            date.setVerticalAlignment(VerticalAlignment.CENTER);
            thinBorder(date);

            text = workbook.createCellStyle();
            text.setVerticalAlignment(VerticalAlignment.CENTER);
            text.setIndention((short) 1);
            thinBorder(text);

            seq = workbook.createCellStyle();
            seq.setAlignment(HorizontalAlignment.CENTER);
            seq.setVerticalAlignment(VerticalAlignment.CENTER);
            thinBorder(seq);
        }

        private static void thinBorder(CellStyle style) {
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
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

    /** 只为了带边框占位：金额格空着不写值（写了 0 会显示成 ¥0.00） */
    private static void blankCell(XSSFRow row, int col, CellStyle style) {
        row.createCell(col).setCellStyle(style);
    }

    private static void dateCell(XSSFRow row, int col, java.time.LocalDate value, Styles styles) {
        var cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(styles.date);
    }
}