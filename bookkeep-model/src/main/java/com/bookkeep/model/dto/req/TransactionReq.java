package com.bookkeep.model.dto.req;

import com.bookkeep.model.validation.MoneyRules;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 新增 / 修改流水。
 * <p>type 必须和分类的 type 一致（Service 校验，不信任前端）。
 */
public record TransactionReq(

        @NotNull(message = "分类不能为空")
        Long categoryId,

        @NotBlank(message = "收支类型不能为空")
        @Pattern(regexp = "income|expense", message = "收支类型只能是 income 或 expense")
        String type,

        @NotNull(message = "金额不能为空")
        @DecimalMin(value = "0.01", message = MoneyRules.RANGE_MESSAGE)
        @DecimalMax(value = "999999.99", message = MoneyRules.RANGE_MESSAGE)
        @Digits(integer = MoneyRules.INTEGER_DIGITS, fraction = MoneyRules.FRACTION_DIGITS,
                message = MoneyRules.RANGE_MESSAGE)
        BigDecimal amount,

        @NotNull(message = "日期不能为空")
        LocalDate transDate,

        @Size(max = 255, message = "备注不能超过 255 个字符")
        String note
) {
}