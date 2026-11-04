package com.bookkeep.model.dto.resp;

import java.math.BigDecimal;
import java.util.List;

/**
 * 只读公示页（GET /api/public/report/{shareToken}，免登录，PLAN §5.9）。
 * <p>只有账本名与金额，<strong>不含任何个人信息</strong>（没有 username / email / userId）：
 * 这个接口一旦上公网就是匿名可访问的，能少暴露一个字段就少一个。
 * <p>{@code transactions} 按日期升序，逐行结余是从上往下读的。
 */
public record PublicReportResp(
        String bookName,
        String month,
        BigDecimal income,
        BigDecimal expense,
        BigDecimal balance,
        BigDecimal totalBalance,
        List<CategoryStatResp> categories,
        List<ReportTransactionResp> transactions
) {
}