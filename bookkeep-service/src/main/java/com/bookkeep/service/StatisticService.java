package com.bookkeep.service;

import com.bookkeep.model.dto.resp.CategoryStatResp;
import com.bookkeep.model.dto.resp.RangeSummaryResp;

import java.util.List;

/**
 * 统计业务（按当前账本）。
 * <p>{@code startDate}/{@code endDate} 都是 {@code yyyy-MM-dd}，都可空：都不传 = 全部。
 * <p>口径只在 {@code StatisticCalculator} 实现一次。
 */
public interface StatisticService {

    RangeSummaryResp summary(String startDate, String endDate);

    /** @param type income / expense，默认 expense（分类占比主要看花在哪） */
    List<CategoryStatResp> category(String startDate, String endDate, String type);
}