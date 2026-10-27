package com.bookkeep.service.constant;

import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;

import java.util.List;

/**
 * 注册 / 新建账本时预置的分类模板
 * <p>只提供静态数据，不碰数据库；落库由后续的 AuthService / BookService 在事务里完成。
 * <p>分类<strong>名称</strong>只是预置默认值，用户可以自定义；{@code type} 只有 income / expense 两种。
 * <p><strong>图标与颜色存的是语义 key，不是具体展示值</strong>：前端用 key 去映射图标组件与主题色，
 * 所以换图标集 / 换主题不需要改数据。前端必须对未知 key 做兜底（默认图标、默认色）。
 * <p>key 约定：小写字母开头，仅含小写字母/数字/下划线。
 */
public final class CategoryTemplate {

    /** book.type 取值 */
    public static final String BOOK_PERSONAL = "personal";
    public static final String BOOK_MERCHANT = "merchant";

    /** category.type 取值 */
    public static final String TYPE_INCOME = "income";
    public static final String TYPE_EXPENSE = "expense";

    /** 模板中的单条分类，字段与 {@code category} 表对应。 */
    public record Seed(String name, String type, String icon, String color, int sortOrder) {
    }

    private static final List<Seed> PERSONAL = List.of(
            new Seed("餐饮", TYPE_EXPENSE, "food", "orange", 1),
            new Seed("交通", TYPE_EXPENSE, "transport", "blue", 2),
            new Seed("购物", TYPE_EXPENSE, "shopping", "pink", 3),
            new Seed("住房", TYPE_EXPENSE, "housing", "green", 4),
            new Seed("娱乐", TYPE_EXPENSE, "entertainment", "purple", 5),
            new Seed("医疗", TYPE_EXPENSE, "medical", "red", 6),
            new Seed("学习", TYPE_EXPENSE, "study", "cyan", 7),
            new Seed("其他", TYPE_EXPENSE, "other", "gray", 8),
            new Seed("工资", TYPE_INCOME, "salary", "green", 9),
            new Seed("奖金", TYPE_INCOME, "bonus", "yellow", 10),
            new Seed("理财", TYPE_INCOME, "invest", "blue", 11),
            new Seed("其他", TYPE_INCOME, "other", "gray", 12)
    );

    private static final List<Seed> MERCHANT = List.of(
            new Seed("采购", TYPE_EXPENSE, "purchase", "orange", 1),
            new Seed("租金", TYPE_EXPENSE, "rent", "green", 2),
            new Seed("员工工资", TYPE_EXPENSE, "staff", "blue", 3),
            new Seed("水电", TYPE_EXPENSE, "utilities", "yellow", 4),
            new Seed("物流", TYPE_EXPENSE, "logistics", "cyan", 5),
            new Seed("税费", TYPE_EXPENSE, "tax", "red", 6),
            new Seed("设备", TYPE_EXPENSE, "equipment", "gray", 7),
            new Seed("营销", TYPE_EXPENSE, "marketing", "purple", 8),
            new Seed("其他", TYPE_EXPENSE, "other", "gray", 9),
            new Seed("销售收入", TYPE_INCOME, "sales", "green", 10),
            new Seed("服务收入", TYPE_INCOME, "service", "blue", 11),
            new Seed("其他", TYPE_INCOME, "other", "gray", 12)
    );

    private CategoryTemplate() {
    }

    /** 按账本类型（personal / merchant）取预置分类，返回不可变列表。 */
    public static List<Seed> of(String bookType) {
        if (bookType == null || bookType.isBlank()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "账本类型不能为空");
        }
        return switch (bookType.trim().toLowerCase()) {
            case BOOK_PERSONAL -> PERSONAL;
            case BOOK_MERCHANT -> MERCHANT;
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "账本类型只能是 personal / merchant");
        };
    }
}
