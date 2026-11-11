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
 * <p>{@code SecurityConfig} 只放 {@code /api/public/report/**}，这是唯一匿名数据出口。
 * <p>token 查不到账本 → 404「公示页不存在或已失效」，绝不能回落到「当前登录用户的账本」的分支上
 * （否则有人就能借着这个口看到别人的账本）。
 * <p>票据图片也挂在这条路径下（{@code .../receipts/{id}}），所以不用改安全配置；四重校验
 * （token 有效 + 账本未删 + 流水未删 + 票据归属）在 {@link ReceiptService#loadPublic} 里，
 * 任一条不满足都是 404，不泄露存在性。
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicController {

    private final PublicReportService publicReportService;
    private final ReceiptService receiptService;

    /** 区间两端可空（都不传 = 全部时间）：{@code ?startDate=2026-09-01&endDate=2026-11-11} */
    @GetMapping("/report/{shareToken}")
    public Result<PublicReportResp> report(@PathVariable String shareToken,
                                          @RequestParam(required = false) String startDate,
                                          @RequestParam(required = false) String endDate) {
        return Result.success(publicReportService.report(shareToken, startDate, endDate));
    }

    @GetMapping("/report/{shareToken}/receipts/{receiptId}")
    public ResponseEntity<Resource> receipt(@PathVariable String shareToken,
                                            @PathVariable Long receiptId) {
        return ReceiptController.image(receiptService.loadPublic(shareToken, receiptId));
    }
}
