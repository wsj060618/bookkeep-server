package com.bookkeep.web.controller;

import com.bookkeep.common.result.Result;
import com.bookkeep.model.dto.resp.PublicReportResp;
import com.bookkeep.service.PublicReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 只读公示页（免登录，PLAN §5.9）。
 * <p>{@code SecurityConfig} 只对 {@code /api/public/report/**} 放行：公网暴露面就这一个只读接口。
 * <p>token 查不到账本 → 404「公示页不存在或已失效」。不要在这里加「回落到当前登录用户账本」的分支，
 * 那会让匿名请求意外看到别人的账。
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicController {

    private final PublicReportService publicReportService;

    @GetMapping("/report/{shareToken}")
    public Result<PublicReportResp> report(@PathVariable String shareToken,
                                          @RequestParam(required = false) String month) {
        return Result.success(publicReportService.report(shareToken, month));
    }
}