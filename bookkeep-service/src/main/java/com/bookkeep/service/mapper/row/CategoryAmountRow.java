package com.bookkeep.service.mapper.row;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 分类占比的聚合行：SQL 只出金额，{@code percent} 在 Java 里算。
 * <p>为什么不在 SQL 里除：除法的舍入、总分母为 0 的兜底、以及「各项相加不必等于 100」的解释都只有一处，
 * 统计接口和公示页复用同一个 {@code StatisticCalculator} 才不会出现两套口径。
 */
@Data
public class CategoryAmountRow {

    private Long categoryId;

    /** 分类已删时 SQL 给出「已删除分类」，保证金额不会因为删分类而对不上 */
    private String categoryName;

    private String categoryIcon;

    private String categoryColor;

    private BigDecimal amount;
}