package com.bookkeep.service.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CSV 解析的纯单元测试。
 * <p>钉住的是「引号内的换行属于单元格」——按行切会把一条流水劈成两半，
 * 前半截没金额、后半截没日期，两边都被当空行丢掉，而且不报错。
 */
class CsvReaderTest {

    @Test
    @DisplayName("引号内的换行不切记录，备注原样保留，行号指向记录起始行")
    void quotedNewlineStaysInCell() {
        String text = "日期,分类,摘要,收入,支出\n"
                + "2024-12-12,打印复印,\"英语听力打印\n数量：40\n单价：0.4\",,16.00\n"
                + "2024-12-20,奖品,班级人员奖励,,201.00\n";

        List<CsvReader.Row> rows = CsvReader.parse(text);
        assertEquals(3, rows.size());
        assertEquals(5, rows.get(1).cells().size(), "跨行的备注仍然只算一条 5 列记录");
        assertEquals("英语听力打印\n数量：40\n单价：0.4", rows.get(1).at(2));
        assertEquals("16.00", rows.get(1).at(4));
        // 行号是记录**起始**行（1 起）：表头 1，第一条 2，第三条在跨行记录之后（第 5 行）
        assertEquals(2, rows.get(1).line());
        assertEquals(5, rows.get(2).line());
    }

    @Test
    @DisplayName("转义的双引号、CRLF、空行、末行无换行都能处理")
    void escapesAndCrlf() {
        String text = "日期,类型,分类,金额,备注\r\n"
                + "2024-10-26,支出,奖品,90.00,\"他说\"\"好的\"\"\r\n第二行\"\r\n"
                + "\r\n"
                + "2024-11-08,支出,活动经费,12.80,";

        List<CsvReader.Row> rows = CsvReader.parse(text);
        assertEquals(3, rows.size(), "空行要丢掉，末行没有换行也要保留");
        assertEquals("他说\"好的\"\n第二行", rows.get(1).at(4));
        assertEquals("", rows.get(2).at(4));
        assertEquals(5, rows.get(2).line());
    }

    @Test
    @DisplayName("全空记录（,,,）也丢掉，不会变成一笔空流水")
    void blankRecordsDropped() {
        List<CsvReader.Row> rows = CsvReader.parse("\n,,\n \n日期,类型\n");
        assertEquals(1, rows.size());
        assertEquals("日期", rows.get(0).at(0));
    }
}
