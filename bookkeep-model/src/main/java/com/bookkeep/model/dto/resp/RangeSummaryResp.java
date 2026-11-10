package com.bookkeep.model.dto.resp;

import java.math.BigDecimal;

/**
 * 区间统计（GET /api/statistics/summary）。
 * <p>{@code startMonth}/{@code endMonth} 为 null 表示该端不限（都不传 = 全部）。
 * <p>{@code balance} 是<strong>区间末</strong>的账户余额（不限结束月份时 = 当前余额）。
 */
public record RangeSummaryResp(
        String startMonth,
        String endMonth,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal balance
) {
}