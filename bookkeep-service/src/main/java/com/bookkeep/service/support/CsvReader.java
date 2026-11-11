package com.bookkeep.service.support;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简 CSV 解析（RFC 4180 里够用的那个子集）：引号内的**换行**和 {@code ""} 转义都要支持。
 * <p>为什么不能按 {@code \n} 切行再拆列：本系统导出时，备注里的换行会写成引号内的真实换行
 * （{@code 2024-12-12,打印复印,"英语资料打印\n数量：40",...}）。先切行就把一条记录劈成两半，
 * 前半截没有金额、后半截没有日期，两边都被当成「不是流水」跳过——表现就是
 * 「导入只导了一半，还不报错」。
 */
public final class CsvReader {

    /** 一条记录；{@code line} 是记录**起始**的物理行号（1 起，报错信息里给人看） */
    public record Row(int line, List<String> cells) {

        public String at(int index) {
            return index >= 0 && index < cells.size() && cells.get(index) != null ? cells.get(index) : "";
        }
    }

    private CsvReader() {
    }

    public static List<Row> parse(String text) {
        List<Row> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        // 这一条记录是否已经有内容（区分真正的空行和 `,,` 这种全空记录）
        boolean touched = false;
        int line = 1;
        int startLine = 1;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else if (c == '\n') {
                    cell.append('\n'); // 引号内的换行属于单元格内容
                    line++;
                } else if (c != '\r') {
                    cell.append(c);
                }
                continue;
            }
            if (c == '"') {
                quoted = true;
                touched = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
                touched = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++; // \r\n 当一个换行
                }
                if (touched || cell.length() > 0) {
                    cells.add(cell.toString());
                    addIfNotBlank(rows, startLine, cells);
                }
                cells.clear();
                cell.setLength(0);
                touched = false;
                line++;
                startLine = line;
            } else {
                cell.append(c);
                touched = true;
            }
        }
        // 最后一行没有以换行结尾
        if (touched || cell.length() > 0) {
            cells.add(cell.toString());
            addIfNotBlank(rows, startLine, cells);
        }
        return rows;
    }

    private static void addIfNotBlank(List<Row> rows, int line, List<String> cells) {
        for (String cell : cells) {
            if (cell != null && !cell.isBlank()) {
                rows.add(new Row(line, List.copyOf(cells)));
                return;
            }
        }
    }
}
