package com.bookkeep.model.dto.resp;

import java.math.BigDecimal;

/**
 * 分类占比（GET /api/statistics/category、公示页的分类块）。
 * <p>{@code percent} 保留两位小数、四舍五入，各项相加可能不等于 100.00（例如三项各 33.33），
 * 前端只拿它做图表标签，不要用它反算金额。
 * <p>带上 {@code categoryIcon}/{@code categoryColor} 是为了饼图图例直接出图标，省一次分类查询（PLAN §5.0 第 5 条）。
 */
public record CategoryStatResp(
        Long categoryId,
        String categoryName,
        String categoryIcon,
        String categoryColor,
        BigDecimal amount,
        BigDecimal percent
) {
}