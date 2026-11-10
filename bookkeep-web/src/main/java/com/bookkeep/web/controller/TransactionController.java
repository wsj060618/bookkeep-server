package com.bookkeep.web.controller;

import com.bookkeep.common.result.PageResult;
import com.bookkeep.common.result.Result;
import com.bookkeep.model.dto.req.BatchIdsReq;
import com.bookkeep.model.dto.req.TransactionQuery;
import com.bookkeep.model.dto.req.TransactionReq;
import com.bookkeep.model.dto.resp.ImportResultResp;
import com.bookkeep.model.dto.resp.TransactionResp;
import com.bookkeep.model.dto.resp.ZipImportResp;
import com.bookkeep.service.TransactionService;
import com.bookkeep.service.export.ReceiptExportService;
import com.bookkeep.service.export.TransactionExcelExportService;
import com.bookkeep.service.export.TransactionExportService;
import com.bookkeep.service.imports.ReceiptZipImportService;
import com.bookkeep.service.imports.TransactionImportService;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedWriter;
import java.io.IOException;
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
    private final TransactionExcelExportService transactionExcelExportService;
    private final TransactionImportService transactionImportService;
    private final ReceiptZipImportService receiptZipImportService;
    private final ReceiptExportService receiptExportService;

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
     * 导出班费收支表（CSV / xlsx / zip，含逐行结余）。
     * <p>{@code format=xlsx} 时返回真正的 Excel：顶部汇总 + 两张分类柱状图 + 明细；
     * {@code format=zip} 时是**账本导出包**（`流水.csv` + `manifest.csv` + 票据图片，可再导入）；
     * 默认 csv。
     * <p>成功时返回文件流而不是 JSON 信封——下载类接口只能这样，前端用 blob 接收。
     * <p>参数错误（开始晚于结束）仍走业务异常：HTTP 200 + JSON {@code code=400}，由前端统一拦截器提示。
     * <p>响应体是 {@link StreamingResponseBody}：校验/取数在 {@code prepare} 里先做完（此时响应还没提交，
     * 出错还能正常返回 JSON），之后逐行写到输出流，几千条流水也不在内存里攒整份文件。
     */
    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(@RequestParam(required = false) LocalDate startDate,
                                                        @RequestParam(required = false) LocalDate endDate,
                                                        @RequestParam(required = false) String format) {
        // format=zip：账本导出（流水.csv + manifest.csv + 票据图片），整本/区间都能再导入
        if ("zip".equalsIgnoreCase(format)) {
            ReceiptExportService.ExportPlan plan = receiptExportService.prepareBookPackage(startDate, endDate);
            StreamingResponseBody zipBody = outputStream -> receiptExportService.write(plan, outputStream);
            return ResponseEntity.ok()
                    .contentType(new MediaType("application", "zip"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(plan.filename(), StandardCharsets.UTF_8)
                            .build()
                            .toString())
                    .body(zipBody);
        }

        TransactionExportService.ExportPlan plan = transactionExportService.prepare(startDate, endDate);
        boolean xlsx = "xlsx".equalsIgnoreCase(format);
        String filename = xlsx
                ? plan.filename().replaceAll("\\.csv$", "") + ".xlsx"
                : plan.filename();

        StreamingResponseBody body = outputStream -> {
            if (xlsx) {
                transactionExcelExportService.write(plan, outputStream);
            } else {
                Writer writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
                transactionExportService.writeCsv(plan, writer);
            }
        };
        return ResponseEntity.ok()
                .contentType(xlsx
                        ? new MediaType("application", "vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                        : new MediaType("text", "csv", StandardCharsets.UTF_8))
                // 文件名含中文，必须交给 ContentDisposition 生成 filename*=UTF-8''... 形式，
                // 手写 header 会在部分浏览器上变成乱码或直接把附件名截断
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(filename, StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(body);
    }

    /**
     * 导入流水（CSV，multipart）。
     * <p>行级失败不阻塞其它行：返回 {@code {success, failed:[{line, reason}]}}，
     * 前端把失败行列出来让用户改完重传；分类不存在**不会**自动创建。
     */
    @PostMapping("/import")
    public Result<ImportResultResp> importFile(@RequestParam("file") MultipartFile file) throws IOException {
        return Result.success(transactionImportService.importFile(file.getOriginalFilename(), file.getBytes()));
    }

    /**
     * 导入压缩包：本系统「导出票据」生成的 zip（manifest.csv + 流水.csv + 图片）。
     * <p>先导流水再挂图；失败分成「流水行」和「票据文件」两栏返回。
     */
    @PostMapping(value = "/import/zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ZipImportResp> importZip(@RequestParam("file") MultipartFile file) throws IOException {
        return Result.success(receiptZipImportService.importZip(file.getOriginalFilename(), file.getBytes()));
    }
}