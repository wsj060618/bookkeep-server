package com.bookkeep.model.dto.req;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.time.LocalDate;

/**
 * 流水分页查询条件（GET /api/transactions 的 query 参数，由 Spring 自动绑定）。
 * <p>分页参数名固定 {@code page}/{@code size}（PLAN §5.0 第 1 条），page 从 1 开始。
 * <p>所有字段都可空：空 = 不加这个筛选条件。
 * <p>日期用 {@link LocalDate}：Spring 默认按 ISO（{@code yyyy-MM-dd}）解析 query 参数，
 * 所以 {@code ?startDate=2026-11-08} 直接可用。这里**故意不加** {@code @DateTimeFormat}——
 * 那个注解在 spring-context 里，而 model 模块只依赖注解包（见 PLAN §4 的依赖清单），
 * 为了一个格式注解放进整个 spring-context 不划算。将来若要换格式，在 controller 上加即可。
 */
@Data
public class TransactionQuery {

    @Min(value = 1, message = "page 从 1 开始")
    private Integer page = 1;

    @Min(value = 1, message = "size 至少为 1")
    @Max(value = 100, message = "size 最多 100")
    private Integer size = 10;

    /** income / expense */
    private String type;

    private Long categoryId;

    /** 起始日期（含），yyyy-MM-dd */
    private LocalDate startDate;

    /** 结束日期（含），yyyy-MM-dd */
    private LocalDate endDate;

    /** 备注关键字，模糊匹配 */
    private String keyword;
}