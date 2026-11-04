package com.bookkeep.model.dto.resp;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 公示页的流水行：比普通列表多一个 {@code balanceAfter}（该行发生后的累计结余）。
 * <p>结余由 MySQL 窗口函数在<strong>全账本范围</strong>内算好，所以即使只查某个月，第一行的结余也是「截至这行」的真实余额，
 * 而不是从 0 开始（PLAN §5.5.1）。
 * <p>不含 id：公示页只读，没有任何按 id 的操作，给出去只会多一个可枚举的东西。
 */
public record ReportTransactionResp(
        LocalDate transDate,
        String type,
        BigDecimal amount,
        String note,
        String categoryName,
        String categoryIcon,
        String categoryColor,
        BigDecimal balanceAfter
) {
}