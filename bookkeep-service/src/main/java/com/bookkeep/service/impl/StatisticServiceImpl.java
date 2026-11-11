package com.bookkeep.service.impl;

import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.model.dto.resp.CategoryStatResp;
import com.bookkeep.model.dto.resp.RangeSummaryResp;
import com.bookkeep.service.CurrentBookService;
import com.bookkeep.service.StatisticService;
import com.bookkeep.service.constant.CategoryPresets;
import com.bookkeep.service.support.StatisticCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/** 统计业务：账本取当前登录用户，口径全部委托 {@link StatisticCalculator}。 */
@Service
@RequiredArgsConstructor
public class StatisticServiceImpl implements StatisticService {

    private final CurrentBookService currentBookService;
    private final StatisticCalculator calculator;

    @Override
    @Transactional(readOnly = true)
    public RangeSummaryResp summary(String startDate, String endDate) {
        return calculator.summaryBetween(currentBookService.currentBookId(),
                StatisticCalculator.resolveDateRange(startDate, endDate));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryStatResp> category(String startDate, String endDate, String type) {
        return calculator.categoriesBetween(currentBookService.currentBookId(),
                StatisticCalculator.resolveDateRange(startDate, endDate),
                resolveType(type));
    }

    private String resolveType(String type) {
        if (!StringUtils.hasText(type)) {
            return CategoryPresets.TYPE_EXPENSE;
        }
        String trimmed = type.trim();
        if (!CategoryPresets.TYPE_INCOME.equals(trimmed) && !CategoryPresets.TYPE_EXPENSE.equals(trimmed)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "收支类型只能是 income 或 expense");
        }
        return trimmed;
    }
}