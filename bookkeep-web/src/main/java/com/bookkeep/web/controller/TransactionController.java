package com.bookkeep.web.controller;

import com.bookkeep.common.result.PageResult;
import com.bookkeep.common.result.Result;
import com.bookkeep.model.dto.req.BatchIdsReq;
import com.bookkeep.model.dto.req.TransactionQuery;
import com.bookkeep.model.dto.req.TransactionReq;
import com.bookkeep.model.dto.resp.TransactionResp;
import com.bookkeep.service.TransactionService;
import com.bookkeep.service.export.TransactionExportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

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
    private final TransactionExportService transactionExportService;

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

    /** 批量删除（流水页勾选后一次删）：返回实际删除条数 */
    @PostMapping("/batch-delete")
    public Result<Integer> deleteBatch(@Valid @RequestBody BatchIdsReq req) {
        return Result.success(transactionService.deleteBatch(req.ids()));
    }

    /**
     * 导出班费收支表（CSV，UTF-8 BOM，含逐行结余）。
     * <p>成功时返回文件流而不是 JSON 信封——下载类接口只能这样，前端用 blob 接收。
     * <p>参数错误（开始晚于结束）仍走业务异常：HTTP 200 + JSON {@code code=400}，由前端统一拦截器提示。
     * <p>响应体是 {@link StreamingResponseBody}：校验/取数在 {@code prepare} 里先做完（此时响应还没提交，
     * 出错还能正常返回 JSON），之后逐行写到输出流，几千条流水也不在内存里攒整份文件。
     */
    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(@RequestParam(required = false) LocalDate startDate,
                                                        @RequestParam(required = false) LocalDate endDate) {
        TransactionExportService.ExportPlan plan = transactionExportService.prepare(startDate, endDate);

        StreamingResponseBody body = outputStream -> {
            Writer writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
            transactionExportService.writeCsv(plan, writer);
        };
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                // 文件名含中文，必须交给 ContentDisposition 生成 filename*=UTF-8''... 形式，
                // 手写 header 会在部分浏览器上变成乱码或直接把附件名截断
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(plan.filename(), StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(body);
    }
}