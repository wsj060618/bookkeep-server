package com.bookkeep.service;

import com.bookkeep.model.dto.resp.CategoryStatResp;
import com.bookkeep.model.dto.resp.RangeSummaryResp;

import java.util.List;

/**
 * 统计业务（按当前账本）。
 * <p>{@code startMonth}/{@code endMonth} 都是 {@code yyyy-MM}，都可空：都不传 = 全部。
 * <p>口径只在 {@code StatisticCalculator} 实现一次。
 */
public interface StatisticService {

    RangeSummaryResp summary(String startMonth, String endMonth);

    /** @param type income / expense，默认 expense（分类占比主要看花在哪） */
    List<CategoryStatResp> category(String startMonth, String endMonth, String type);
}