package com.bookkeep.web.controller;

import com.bookkeep.common.result.PageResult;
import com.bookkeep.common.result.Result;
import com.bookkeep.model.dto.req.TransactionQuery;
import com.bookkeep.model.dto.req.TransactionReq;
import com.bookkeep.model.dto.resp.TransactionResp;
import com.bookkeep.service.TransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 流水接口（按当前账本）。
 * <p>{@code @Validated} 是为了让 {@link TransactionQuery} 上的 {@code @Min/@Max} 在
 * 绑定 query 参数时生效（{@code @Valid} 只能校验请求体；查询对象的校验走 BindException 那条路，
 * 由 GlobalExceptionHandler 统一转成 400）。
 */
@Validated
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    /**
     * 分页查询。
     * <p>示例：{@code GET /api/transactions?page=1&size=10&type=expense&startDate=2026-11-01&endDate=2026-11-30&keyword=聚餐}
     */
    @GetMapping
    public Result<PageResult<TransactionResp>> page(@Valid TransactionQuery query) {
        return Result.success(transactionService.page(query));
    }

    /** 当前账本余额（Σ收入 − Σ支出），前端顶部「结余」用。 */
    @GetMapping("/balance")
    public Result<BigDecimal> balance() {
        return Result.success(transactionService.balance());
    }

    @PostMapping
    public Result<TransactionResp> create(@Valid @RequestBody TransactionReq req) {
        return Result.success(transactionService.create(req));
    }

    @PutMapping("/{id}")
    public Result<TransactionResp> update(@PathVariable Long id, @Valid @RequestBody TransactionReq req) {
        return Result.success(transactionService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        transactionService.delete(id);
        return Result.success();
    }
}