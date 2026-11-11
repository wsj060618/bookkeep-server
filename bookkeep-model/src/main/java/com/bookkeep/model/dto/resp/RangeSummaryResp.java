package com.bookkeep.model.dto.resp;

import java.math.BigDecimal;

/**
 * 区间统计（GET /api/statistics/summary）。
 * <p>{@code startDate}/{@code endDate} 为 {@code yyyy-MM-dd}，null 表示该端不限（都不传 = 全部）。
 * <p>{@code balance} 是<strong>区间末</strong>的账户余额（不限结束日时 = 当前余额）。
 */
public record RangeSummaryResp(
        String startDate,
        String endDate,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal balance
) {
}
