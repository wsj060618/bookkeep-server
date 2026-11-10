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
 * 新增 / 修改流水的请求体。
 * <p>金额用 {@link BigDecimal}，序列化时全局按字符串输出（见 JacksonConfig），避免 JS 双精度误差。
 * <p>{@code type} 必须与 {@code categoryId} 对应分类的 type 一致；Service 会校验，
 * 不信任前端传来的类型（前端切换分类时可能残留旧值）。
 *
 * @param categoryId 分类 id，必须属于当前账本
 * @param type       income / expense
 * @param amount     金额，0.01 ~ 999,999.99，最多两位小数（{@link MoneyRules}）
 * @param transDate  业务日期 yyyy-MM-dd（可以补录过去的日子，不能选未来？—— 允许，不做限制）
 * @param note       备注，≤255
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