package com.bookkeep.model.dto.resp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 公示页的流水行：比普通列表多一个 {@code balanceAfter}（该行发生后的累计结余）。
 * <p>结余是 MySQL 窗口函数在**全账本范围**内算好，所以即使只查某个月，第一行的结余也是「截至这行」的真实余额，
 * 而不是从 0 开始（PLAN §5.5.1）。
 * <p>不含流水 id：公示页没有按 id 的操作，给出去只会多一个可枚举的东西。
 * <p>{@code receipts} 只给「票据 id + 标题」，图片走 {@code /api/public/report/{token}/receipts/{id}}；
 * {@code receiptCount} 与列表长度一致，方便前端在还没展开时先显示数量。
 */
public record ReportTransactionResp(
        LocalDate transDate,
        String type,
        BigDecimal amount,
        String note,
        String categoryName,
        String categoryIcon,
        String categoryColor,
        BigDecimal balanceAfter,
        int receiptCount,
        List<PublicReceiptResp> receipts
) {
}