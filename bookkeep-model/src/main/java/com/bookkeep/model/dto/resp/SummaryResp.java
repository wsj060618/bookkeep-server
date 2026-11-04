package com.bookkeep.model.dto.resp;

import java.math.BigDecimal;

/**
 * 月度统计（GET /api/statistics/summary）。
 * <p>{@code balance} 是<strong>本月</strong>收支差，{@code totalBalance} 是全账本累计余额——语义不同，前端卡片要分开显示。
 * <p>{@code month} 回显实际统计的月份：请求不传时后端按当月补全，前端不用自己猜。
 */
public record SummaryResp(
        String month,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal balance,
        BigDecimal totalBalance
) {
}