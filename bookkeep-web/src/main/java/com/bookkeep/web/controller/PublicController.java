package com.bookkeep.web.controller;

import com.bookkeep.common.result.Result;
import com.bookkeep.model.dto.resp.PublicReportResp;
import com.bookkeep.service.PublicReportService;
import com.bookkeep.service.ReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 只读公示页（免登录，PLAN §5.9 / §10.9）。
 * <p>{@code SecurityConfig} 只对 {@code /api/public/report/**} 放行：公网暴露面就这一小块。
 * <p>token 查不到账本 → 404「公示页不存在或已失效」。不要在这里加「回落到当前登录用户账本」的分支，
 * 那会让匿名请求意外看到别人的账本。
 * <p>票据图片也挂在这条路径下（{@code .../receipts/{id}}），所以不用改安全白名单；
 * 校验（token 有效 + 账本未删 + 流水未删 + 票据归属）在 {@link ReceiptService#loadPublic} 里，
 * 任一不满足一律 404，不暴露「存在但无权」。
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicController {

    private final PublicReportService publicReportService;
    private final ReceiptService receiptService;

    @GetMapping("/report/{shareToken}")
    public Result<PublicReportResp> report(@PathVariable String shareToken,
                                          @RequestParam(required = false) String month) {
        return Result.success(publicReportService.report(shareToken, month));
    }

    @GetMapping("/report/{shareToken}/receipts/{receiptId}")
    public ResponseEntity<Resource> receipt(@PathVariable String shareToken,
                                            @PathVariable Long receiptId) {
        return ReceiptController.image(receiptService.loadPublic(shareToken, receiptId));
    }
}