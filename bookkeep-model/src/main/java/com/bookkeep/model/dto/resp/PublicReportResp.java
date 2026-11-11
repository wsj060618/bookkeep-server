package com.bookkeep.model.dto.resp;

import java.math.BigDecimal;
import java.util.List;

/**
 * 只读公示页（GET /api/public/report/{shareToken}，免登录，PLAN §5.9）。
 * <p>**一条链接可以带多本账本**：{@code books} 里每本一套数字，前端用标签页切换。
 * <p>只出账本名与金额，<strong>不含任何个人信息</strong>（没有 username / email / userId）。
 * <p>{@code startDate}/{@code endDate} 对所有账本一致（同一组查询参数），
 * 为 null 表示该端不限；都不传 = 全部时间。没数据的账本给 0.00 与空列表。
 */
public record PublicReportResp(
        /** 链接到期时间 yyyy-MM-dd HH:mm:ss；前端可以提示「本链接有效期至」 */
        String expiresAt,
        List<BookReport> books
) {

    /**
     * 单本账本在公示页上的内容。
     * <p>{@code balance} 是<strong>区间末</strong>的账户余额，不是区间收支差（同统计页口径）。
     * <p>{@code startDate}/{@code endDate} 是回显的 {@code yyyy-MM-dd}，null = 该端不限。
     * <p>{@code transactions} 按日期**升序**，逐行结余只有从上往下读才成立。
     */
    public record BookReport(
            Long bookId,
            String bookName,
            String startDate,
            String endDate,
            BigDecimal income,
            BigDecimal expense,
            BigDecimal balance,
            List<CategoryStatResp> expenseCategories,
            List<CategoryStatResp> incomeCategories,
            List<ReportTransactionResp> transactions
    ) {
    }
}
