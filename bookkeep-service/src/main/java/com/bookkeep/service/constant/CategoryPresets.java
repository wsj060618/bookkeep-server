package com.bookkeep.service.constant;

import java.util.List;

/**
 * 内置班级分类预置（代码常量，不入库）。
 * <p>只有一种账本类型（class）；icon/color 存语义 key，由前端映射样式。
 */
public final class CategoryPresets {

    /** book.type 取值，当前只有班级账本 */
    public static final String BOOK_CLASS = "class";

    /** 注册时不填账本名就用这个 */
    public static final String DEFAULT_BOOK_NAME = "班级账本";

    /** category.type 取值 */
    public static final String TYPE_INCOME = "income";
    public static final String TYPE_EXPENSE = "expense";

    /**
     * 内置模板的**虚拟 id**：不入库（见 {@code category_template} 表注释），
     * 内容即 {@link #classCategories()}。响应里统一用 0 表示「内置班级模板」，
     * 写入时再翻译成 {@code book.category_template_id = NULL}。
     */
    public static final long BUILTIN_ID = 0L;

    public static final String BUILTIN_NAME = "班级班费";

    /** 模板中的单条分类，字段与 category 表对应 */
    public record Seed(String name, String type, String icon, String color, int sortOrder) {
    }

    /** 班级模板：7 条支出 + 4 条收入。故意没有「其他」——那是记一笔下拉里的虚拟入口 */
    private static final List<Seed> CLASS = List.of(
            new Seed("班会物资", TYPE_EXPENSE, "supplies", "orange", 1),
            new Seed("活动经费", TYPE_EXPENSE, "activity", "purple", 2),
            new Seed("聚餐", TYPE_EXPENSE, "dining", "red", 3),
            new Seed("打印复印", TYPE_EXPENSE, "print", "cyan", 4),
            new Seed("场地", TYPE_EXPENSE, "venue", "green", 5),
            new Seed("交通", TYPE_EXPENSE, "transport", "blue", 6),
            new Seed("奖品", TYPE_EXPENSE, "prize", "yellow", 7),
            new Seed("收取班费", TYPE_INCOME, "fee", "green", 8),
            new Seed("学院拨款", TYPE_INCOME, "subsidy", "blue", 9),
            new Seed("活动赞助", TYPE_INCOME, "sponsor", "purple", 10),
            new Seed("结余转入", TYPE_INCOME, "carryover", "cyan", 11)
    );

    private CategoryPresets() {
    }

    /** 取班级分类模板（不可变列表）。 */
    public static List<Seed> classCategories() {
        return CLASS;
    }
}