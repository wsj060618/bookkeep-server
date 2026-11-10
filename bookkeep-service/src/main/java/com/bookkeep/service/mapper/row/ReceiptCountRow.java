package com.bookkeep.service.mapper.row;

import lombok.Data;

/** 一笔流水的票据张数（列表徽标批量查询的投影） */
@Data
public class ReceiptCountRow {

    private Long transactionId;

    private Long cnt;
}