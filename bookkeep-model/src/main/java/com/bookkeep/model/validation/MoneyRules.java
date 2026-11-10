package com.bookkeep.model.validation;

import java.math.BigDecimal;

/**
 * 单笔金额的唯一口径（记一笔 / 导入共用）：0.01 ~ 999,999.99 元，最多两位小数。
 * <p>上限不是为了限制业务，而是防止溢出：库里的 `amount` / `balance_after` 都是 `DECIMAL(12,2)`，
 * 以前前端放过 10 位整数（`\d{1,10}`），填个 99999999999 就会把累加结余顶出列宽，
 * 用户看到的还是一个「金额最多两位小数」的莫名提示。现在超范围直接告诉他合法区间。
 */
public final class MoneyRules {

    /** 上限（元），与 DECIMAL(12,2) 留足余量，班级账本单笔到不了这个量级 */
    public static final BigDecimal MAX = new BigDecimal("999999.99");

    /** 整数位数：与上限一致，注解里不能写表达式，所以单独列一个常量 */
    public static final int INTEGER_DIGITS = 6;

    public static final int FRACTION_DIGITS = 2;

    public static final String RANGE_MESSAGE = "金额需在 0.01 ~ 999,999.99 元之间，最多两位小数";

    private MoneyRules() {
    }
}