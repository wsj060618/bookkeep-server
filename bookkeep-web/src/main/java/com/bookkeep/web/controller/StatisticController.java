package com.bookkeep.web.controller;

import com.bookkeep.common.result.Result;
import com.bookkeep.model.dto.resp.CategoryStatResp;
import com.bookkeep.model.dto.resp.RangeSummaryResp;
import com.bookkeep.service.StatisticService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 统计接口（按当前账本）。
 * <p>{@code startMonth}/{@code endMonth} 都是 {@code yyyy-MM}，都可空：都不传 = 全部时间。
 */
@RestController
@RequestMapping("/api/statistics")
@RequiredArgsConstructor
public class StatisticController {

    private final StatisticService statisticService;

    /** 区间收入 / 支出 / 期末结余：{@code GET /api/statistics/summary?startMonth=2026-09&endMonth=2026-11} */
    @GetMapping("/summary")
    public Result<RangeSummaryResp> summary(@RequestParam(required = false) String startMonth,
                                            @RequestParam(required = false) String endMonth) {
        return Result.success(statisticService.summary(startMonth, endMonth));
    }

    /** 区间分类占比；type 默认 expense：{@code /api/statistics/category?type=income} */
    @GetMapping("/category")
    public Result<List<CategoryStatResp>> category(@RequestParam(required = false) String startMonth,
                                                   @RequestParam(required = false) String endMonth,
                                                   @RequestParam(required = false) String type) {
        return Result.success(statisticService.category(startMonth, endMonth, type));
    }
}