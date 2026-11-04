package com.bookkeep.service;

import com.bookkeep.model.dto.resp.CategoryStatResp;
import com.bookkeep.model.dto.resp.SummaryResp;

import java.util.List;

/**
 * 统计业务（按当前账本）。
 * <p>口径只在 {@code StatisticCalculator} 里实现一次，这里只负责「取当前账本 + 校验参数」。
 * <p>{@code month} 传 {@code yyyy-MM}，不传则取当月；格式错抛 400。
 */
public interface StatisticService {

    SummaryResp summary(String month);

    /** @param type income / expense，不传按 expense（班费最关心的是花在哪） */
    List<CategoryStatResp> category(String month, String type);
}