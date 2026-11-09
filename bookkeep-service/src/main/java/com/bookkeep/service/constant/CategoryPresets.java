package com.bookkeep.service.constant;

import java.util.List;

/**
 * 内置的班级分类预置（代码常量，不入库）。
 * <p>为什么叫 Presets 而不是 Template：数据库里已经有 {@code CategoryTemplate}（用户自建模板）这个实体，
 * 同名会撞；这里的语义是「内置预置」，两者内容在 {@code BUILTIN_ID} 上合并。
 * <p>只提供静态数据，不碰数据库；落库由 {@code AuthService} 在事务里完成。
 * <p><strong>只有一种账本类型</strong>（{@link #BOOK_CLASS}）：这个项目是给生活委员记班费用的，
 * 个人版 / 商家版的模板在需求收敛时被删掉了。{@code book.type} 列保留并固定写 {@code class}，
 * 将来真要做其它账本类型，只需在这里加一套模板 + 放开类型字段。
 * <p>分类<strong>名称</strong>只是预置默认值，用户可以自定义；{@code type} 只有 income / expense 两种。
 * <p><strong>图标与颜色存的是语义 key，不是具体样式</strong>：前端用 key 去映射图标组件与主题色，
 * 所以换图标库 / 换主题不需要改数据。前端必须对未知 key 做兜底（默认图标、默认色）。
 * <p>key 约定：小写字母开头，仅含小写字母/数字/下划线。
 */
public final class CategoryPresets {

    /** book.type 取值。当前项目只有这一种账本。 */
    public static final String BOOK_CLASS = "class";

    /** 默认账本名：注册时前端不填 bookName 就用这个 */
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

    /** 模板中的单条分类，字段与 {@code category} 表对应。 */
    public record Seed(String name, String type, String icon, String color, int sortOrder) {
    }

    /**
     * 班级模板：7 条支出 + 4 条收入，共 11 条。
     * <p>支出按「班费实际会花在哪」排，收入里的「结余转入」是给换届用的：
     * 新生活委员建账后第一笔记「上学期结余 X 元」，历史就能接上。
     * <p><strong>故意没有「其他」</strong>：「其他」是「记一笔」下拉末尾的虚拟入口，
     * 选中就弹窗现建分类（要图标有图标），所以模板/预置分类里不需要这条。
     */
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