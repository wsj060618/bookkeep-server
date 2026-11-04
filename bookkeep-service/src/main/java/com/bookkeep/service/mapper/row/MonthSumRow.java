package com.bookkeep.service.mapper.row;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 某个月的收入/支出合计。
 * <p>一条 SQL 同时出两个数，避免「查了收入再查支出」两次往返。
 */
@Data
public class MonthSumRow {

    private BigDecimal income;

    private BigDecimal expense;
}