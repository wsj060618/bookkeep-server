package com.bookkeep.service.impl;

import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.model.dto.resp.CategoryStatResp;
import com.bookkeep.model.dto.resp.SummaryResp;
import com.bookkeep.service.CurrentBookService;
import com.bookkeep.service.StatisticService;
import com.bookkeep.service.constant.CategoryTemplate;
import com.bookkeep.service.support.StatisticCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/** 统计业务实现：账本来自当前登录用户，口径全部委托 {@link StatisticCalculator}。 */
@Service
@RequiredArgsConstructor
public class StatisticServiceImpl implements StatisticService {

    private final CurrentBookService currentBookService;
    private final StatisticCalculator calculator;

    @Override
    @Transactional(readOnly = true)
    public SummaryResp summary(String month) {
        return calculator.summaryOf(currentBookService.currentBookId(),
                StatisticCalculator.resolveMonth(month));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryStatResp> category(String month, String type) {
        Long bookId = currentBookService.currentBookId();
        return calculator.categoriesOf(bookId,
                StatisticCalculator.resolveMonth(month),
                resolveType(type));
    }

    private String resolveType(String type) {
        if (!StringUtils.hasText(type)) {
            return CategoryTemplate.TYPE_EXPENSE;
        }
        String trimmed = type.trim();
        if (!CategoryTemplate.TYPE_INCOME.equals(trimmed) && !CategoryTemplate.TYPE_EXPENSE.equals(trimmed)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "收支类型只能是 income 或 expense");
        }
        return trimmed;
    }
}