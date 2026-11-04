package com.bookkeep.web.controller;

import com.bookkeep.common.result.Result;
import com.bookkeep.model.dto.resp.CategoryStatResp;
import com.bookkeep.model.dto.resp.SummaryResp;
import com.bookkeep.service.StatisticService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 统计接口（按当前账本）。
 * <p>{@code month} 传 {@code yyyy-MM}，不传取当月；格式错返回 400（不是空数据）。
 * <p>参数用 String 接收而不是 {@code YearMonth}：{@code YearMonth} 的默认绑定格式与契约不一致，
 * 而且格式错误要靠 {@code StatisticCalculator} 给出中文提示。
 */
@RestController
@RequestMapping("/api/statistics")
@RequiredArgsConstructor
public class StatisticController {

    private final StatisticService statisticService;

    /** 本月收入 / 支出 / 本月结余 + 累计余额：{@code GET /api/statistics/summary?month=2026-08} */
    @GetMapping("/summary")
    public Result<SummaryResp> summary(@RequestParam(required = false) String month) {
        return Result.success(statisticService.summary(month));
    }

    /** 分类占比：{@code GET /api/statistics/category?month=2026-08&type=expense}（type 默认为 expense） */
    @GetMapping("/category")
    public Result<List<CategoryStatResp>> category(@RequestParam(required = false) String month,
                                                  @RequestParam(required = false) String type) {
        return Result.success(statisticService.category(month, type));
    }
}