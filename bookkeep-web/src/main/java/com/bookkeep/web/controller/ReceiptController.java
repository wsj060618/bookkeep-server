package com.bookkeep.web.controller;

import com.bookkeep.common.result.Result;
import com.bookkeep.model.dto.req.ReceiptRenameReq;
import com.bookkeep.model.dto.resp.ReceiptImportResp;
import com.bookkeep.model.dto.resp.ReceiptResp;
import com.bookkeep.service.ReceiptService;
import com.bookkeep.service.export.ReceiptExportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 流水票据接口（PLAN §10.9）。全部要登录：{@code /api/transactions/**} 不在白名单里。
 * <p>图片用 {@code inline} + {@code nosniff} 返回：{@code nosniff} 防止浏览器把内容当别的类型执行
 * （哪怕魔数校验被绕过，这一层也会挡住「当成 HTML 渲染」）。
 * <p>注意 {@code /receipts/export}（账本级）与 {@code /{id}/receipts/export}（单笔）是两条路：
 * 前者按当前账本 + 日期区间，后者只打这一笔。
 */
@RestController
@RequestMapping("/api/transactions")
@RequiredArgsConstructor
public class ReceiptController {

    private final ReceiptService receiptService;
    private final ReceiptExportService receiptExportService;

    /** 上传一张（多选由前端逐张调用：一次一张，失败只影响那一张） */
    @PostMapping("/{transactionId}/receipts")
    public Result<ReceiptResp> upload(@PathVariable Long transactionId,
                                      @RequestParam("file") MultipartFile file,
                                      @RequestParam(required = false) String title) throws IOException {
        return Result.success(receiptService.upload(
                transactionId, file.getOriginalFilename(), file.getBytes(), title));
    }

    @GetMapping("/{transactionId}/receipts")
    public Result<List<ReceiptResp>> list(@PathVariable Long transactionId) {
        return Result.success(receiptService.list(transactionId));
    }

    /**
     * 预览/下载单张（inline，浏览器里直接看图）。
     * <p>{@code ?size=thumb} 给画廊用的 480px 缩略图；不传 = 原图。
     */
    @GetMapping("/{transactionId}/receipts/{receiptId}")
    public ResponseEntity<Resource> download(@PathVariable Long transactionId,
                                             @PathVariable Long receiptId,
                                             @RequestParam(required = false) String size) {
        boolean thumb = isThumb(size);
        return image(receiptService.load(transactionId, receiptId, thumb), thumb, false);
    }

    @PutMapping("/{transactionId}/receipts/{receiptId}")
    public Result<Void> rename(@PathVariable Long transactionId,
                               @PathVariable Long receiptId,
                               @Valid @RequestBody ReceiptRenameReq req) {
        receiptService.rename(transactionId, receiptId, req.title());
        return Result.success();
    }

    @DeleteMapping("/{transactionId}/receipts/{receiptId}")
    public Result<Void> delete(@PathVariable Long transactionId,
                               @PathVariable Long receiptId) {
        receiptService.delete(transactionId, receiptId);
        return Result.success();
    }

    /** 单笔票据的压缩包导入：包里放这一笔的多张图片（可都在一个子文件夹里） */
    @PostMapping(value = "/{transactionId}/receipts/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<ReceiptImportResp> importReceipts(@PathVariable Long transactionId,
                                                    @RequestParam("file") MultipartFile file) throws IOException {
        return Result.success(receiptService.importZip(transactionId, file.getOriginalFilename(), file.getBytes()));
    }

    /** 票据导出（账本级，可带区间）：**只有图片**，不带 manifest / 流水.csv */
    @GetMapping("/receipts/export")
    public ResponseEntity<StreamingResponseBody> exportBook(@RequestParam(required = false) LocalDate startDate,
                                                            @RequestParam(required = false) LocalDate endDate) {
        return zip(receiptExportService.prepareBookImages(startDate, endDate));
    }

    /** 单笔导出：这一笔的票据打成 zip，**只有图片**，统一放一个「图片/」文件夹里 */
    @GetMapping("/{transactionId}/receipts/export")
    public ResponseEntity<StreamingResponseBody> exportSingle(@PathVariable Long transactionId) {
        return zip(receiptExportService.prepareSingle(transactionId));
    }

    /* ---------- 内部 ---------- */

    private ResponseEntity<StreamingResponseBody> zip(ReceiptExportService.ExportPlan plan) {
        StreamingResponseBody body = outputStream -> receiptExportService.write(plan, outputStream);
        return ResponseEntity.ok()
                .contentType(new MediaType("application", "zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(plan.filename(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(body);
    }

    static ResponseEntity<Resource> image(ReceiptService.ReceiptFile file) {
        return image(file, false, false);
    }

    static boolean isThumb(String size) {
        return "thumb".equalsIgnoreCase(size);
    }

    /**
     * 图片响应：inline + 长缓存。
     * <p>长缓存是安全的：票据 id 的字节永不改变（只有上传/改名/删除，没有「替换图片」），
     * 所以同一个地址的内容不会变；改名只动标题不动 URL 里的字节。
     * <p>匿名公示页只给 10 分钟：分享链接 3 天就失效，浏览器不能比链接活得久。
     */
    static ResponseEntity<Resource> image(ReceiptService.ReceiptFile file, boolean thumb, boolean anonymous) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.filename(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                // nosniff 由 Spring Security 的默认安全头统一输出，这里不要再写一遍（会变成两个同名头）
                .cacheControl(cacheControl(thumb, anonymous))
                .body(new FileSystemResource(file.path()));
    }

    private static CacheControl cacheControl(boolean thumb, boolean anonymous) {
        if (anonymous) {
            return CacheControl.maxAge(10, TimeUnit.MINUTES).cachePrivate();
        }
        return thumb
                ? CacheControl.maxAge(7, TimeUnit.DAYS).cachePrivate()
                : CacheControl.maxAge(1, TimeUnit.DAYS).cachePrivate();
    }
}