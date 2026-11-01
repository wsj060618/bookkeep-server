package com.bookkeep.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 流水（表名 `transaction` 是 SQL 关键字，所以 {@code @TableName} 用反引号包住）。
 * <p>{@code type} 是冗余列：值一定等于分类的 type，冗余出来是为了统计不用 join category。
 * <p>{@code transDate} 是<strong>业务日期</strong>（聚餐发生的日子），不是录入时间——
 * 出门在外没法记账时，回宿舍补录仍然记在真实发生日，统计和公示才正确。
 */
@Data
@TableName("`transaction`")
public class Transaction {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long bookId;

    private Long categoryId;

    /** 收支类型：income / expense，必须与所属分类一致（由 Service 校验）。 */
    private String type;

    /** 金额，DECIMAL(12,2)，禁止 double。 */
    private BigDecimal amount;

    private String note;

    /** 业务日期 yyyy-MM-dd。 */
    private LocalDate transDate;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}