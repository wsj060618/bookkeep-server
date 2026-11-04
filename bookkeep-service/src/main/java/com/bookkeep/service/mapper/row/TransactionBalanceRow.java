package com.bookkeep.service.mapper.row;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 带逐行结余的流水行（导出 CSV 与公示页共用）。
 * <p>{@code balanceAfter} 由窗口函数算出，注意它是「截至这一笔」的余额，与行的排序严格绑定。
 */
@Data
public class TransactionBalanceRow {

    private LocalDate transDate;

    private String type;

    private BigDecimal amount;

    private String note;

    private String categoryName;

    private String categoryIcon;

    private String categoryColor;

    private BigDecimal balanceAfter;
}