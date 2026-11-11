package com.bookkeep.service.support;

import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.model.dto.TemplateDoc;
import com.bookkeep.service.constant.CategoryPresets;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 分类模板 JSON 文档的清洗与索引。
 * <p>导出方一定合规，但导入的文件可能是手改过的，所以统一在这里兜底：
 * 方向认中英文、空名丢掉、同名同方向只留第一条、图标颜色缺失给默认值。
 */
public final class TemplateDocs {

    /** 一套模板最多 100 条（与 CategoryTemplateServiceImpl.MAX_ITEMS 一致） */
    public static final int MAX_ITEMS = 100;

    /** 与 category_template / category_template_item 的列宽一致 */
    private static final int NAME_MAX = 50;

    private static final String DEFAULT_ICON = "other";
    private static final String DEFAULT_COLOR = "gray";

    private TemplateDocs() {
    }

    /** 严格版（导入模板用）：洗不出一条分类、版本不认识就直接 400 */
    public static TemplateDoc normalize(TemplateDoc doc) {
        if (doc == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "模板文件是空的");
        }
        if (doc.version() > TemplateDoc.VERSION) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "模板文件版本（v" + doc.version() + "）比当前系统新，请先升级系统");
        }
        List<TemplateDoc.Item> items = clean(doc.items());
        if (items.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "模板文件里没有可导入的分类");
        }
        if (items.size() > MAX_ITEMS) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "一套模板最多 " + MAX_ITEMS + " 个分类");
        }
        String name = doc.name() == null ? "" : doc.name().trim();
        if (name.isEmpty()) {
            name = "导入的模板";
        }
        if (name.length() > NAME_MAX) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "模板名不能超过 " + NAME_MAX + " 个字符");
        }
        return new TemplateDoc(TemplateDoc.VERSION, name, items);
    }

    /** 宽松版（账本包里那份 JSON 只用来查图标颜色）：坏条目跳过，不影响导入 */
    public static Map<String, TemplateDoc.Item> index(List<TemplateDoc.Item> items) {
        Map<String, TemplateDoc.Item> map = new LinkedHashMap<>();
        for (TemplateDoc.Item item : clean(items)) {
            map.put(key(item.type(), item.name()), item);
        }
        return map;
    }

    public static String key(String type, String name) {
        return type + "|" + name;
    }

    /** 条目清洗：去空名、方向归一、图标颜色兜底、同名同方向去重、按数组顺序重排 sortOrder */
    static List<TemplateDoc.Item> clean(List<TemplateDoc.Item> items) {
        List<TemplateDoc.Item> result = new ArrayList<>();
        if (items == null) {
            return result;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (TemplateDoc.Item item : items) {
            if (item == null) {
                continue;
            }
            String name = item.name() == null ? "" : item.name().trim();
            if (name.isEmpty() || name.length() > NAME_MAX) {
                continue;
            }
            String type = typeOf(item.type());
            if (type == null || !seen.add(key(type, name))) {
                continue;
            }
            result.add(new TemplateDoc.Item(name, type,
                    StringUtils.hasText(item.icon()) ? item.icon().trim() : DEFAULT_ICON,
                    StringUtils.hasText(item.color()) ? item.color().trim() : DEFAULT_COLOR,
                    result.size() + 1));
        }
        return result;
    }

    private static String typeOf(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "income", "收入" -> CategoryPresets.TYPE_INCOME;
            case "expense", "支出" -> CategoryPresets.TYPE_EXPENSE;
            default -> null;
        };
    }
}
