package com.bookkeep.model.dto.resp;

import com.bookkeep.model.entity.Category;
import com.bookkeep.model.entity.Transaction;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 流水响应体。
 * <p>带 {@code categoryName}/{@code categoryIcon}/{@code categoryColor}：列表直接给展示所需名称，
 * 前端不做二次查询（PLAN §5.0 第 5 条）。这是最容易漏、一联调就暴露的字段。
 * <p>{@code amount} 是 BigDecimal，由 JacksonConfig 全局按**字符串**序列化（{@code "620.00"}），
 * 前端不要 parseFloat 后累加。
 */
public record TransactionResp(
        Long id,
        String type,
        BigDecimal amount,
        LocalDate transDate,
        String note,
        Long categoryId,
        String categoryName,
        String categoryIcon,
        String categoryColor
) {

    /**
     * @param category 可能为 null（分类被删掉但流水还留着），此时名称给「已删除分类」，
     *                 保证前端列表不会出现空白格
     */
    public static TransactionResp from(Transaction transaction, Category category) {
        return new TransactionResp(
                transaction.getId(),
                transaction.getType(),
                transaction.getAmount(),
                transaction.getTransDate(),
                transaction.getNote(),
                transaction.getCategoryId(),
                category == null ? "已删除分类" : category.getName(),
                category == null ? "other" : category.getIcon(),
                category == null ? "gray" : category.getColor()
        );
    }
}