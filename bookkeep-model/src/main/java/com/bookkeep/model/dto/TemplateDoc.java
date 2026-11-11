package com.bookkeep.model.dto;

import java.util.List;

/**
 * 分类模板的 JSON 文档：既是「导出模板」下载的文件格式，也是账本包里
 * {@code 分类模板.json} 的内容。
 * <p>刻意不带任何 id —— 跨账号/跨库导入时主键没有意义，图标和颜色都靠语义 key
 * （见前端 {@code utils/category.ts} 的映射表）。
 */
public record TemplateDoc(
        int version,
        String name,
        List<Item> items
) {

    /** 当前版本；将来字段变了就用它拒绝不认识的文件 */
    public static final int VERSION = 1;

    /**
     * @param type      income / expense（也接受「收入 / 支出」）
     * @param icon      图标语义 key，缺失时给 other
     * @param color     颜色语义 key，缺失时给 gray
     * @param sortOrder 展示顺序，导入时按数组顺序重排
     */
    public record Item(String name, String type, String icon, String color, Integer sortOrder) {
    }
}
