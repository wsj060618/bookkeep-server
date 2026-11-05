package com.bookkeep.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.model.dto.resp.CategoryStatResp;
import com.bookkeep.model.dto.resp.PublicReportResp;
import com.bookkeep.model.dto.resp.ReportTransactionResp;
import com.bookkeep.model.dto.resp.SummaryResp;
import com.bookkeep.model.entity.Book;
import com.bookkeep.service.PublicReportService;
import com.bookkeep.service.constant.CategoryTemplate;
import com.bookkeep.service.mapper.BookMapper;
import com.bookkeep.service.mapper.TransactionMapper;
import com.bookkeep.service.mapper.row.TransactionBalanceRow;
import com.bookkeep.service.support.StatisticCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/**
 * 公示页实现。
 * <p>免登录路径，因此<strong>不能</strong>调 {@code CurrentBookService} / {@code SecurityUtil}：
 * 账本只能从 token 反查，查不到就是 404，绝不允许回落到「当前登录用户的账本」。
 * <p>{@code selectOne} 由 MyBatis-Plus 自动拼 {@code deleted = 0}：账本注销后旧链接立即失效。
 */
@Service
@RequiredArgsConstructor
public class PublicReportServiceImpl implements PublicReportService {

    private final BookMapper bookMapper;
    private final TransactionMapper transactionMapper;
    private final StatisticCalculator calculator;

    @Override
    @Transactional(readOnly = true)
    public PublicReportResp report(String shareToken, String month) {
        // 过期判断放在 SQL 里：`share_token_expires_at > now`，过期 / 从未分享都查不到 → 统一 404
        Book book = bookMapper.selectOne(Wrappers.<Book>query()
                .eq("share_token", shareToken)
                .gt("share_token_expires_at", LocalDateTime.now()));
        if (book == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "公示页不存在或已失效");
        }

        YearMonth yearMonth = StatisticCalculator.resolveMonth(month);
        SummaryResp summary = calculator.summaryOf(book.getId(), yearMonth);
        // 公示页只展示「花在哪」，收入分类不画饼图
        List<CategoryStatResp> categories = calculator.categoriesOf(
                book.getId(), yearMonth, CategoryTemplate.TYPE_EXPENSE);

        List<ReportTransactionResp> transactions = transactionMapper
                .selectMonthWithRunningBalance(book.getId(), summary.month())
                .stream()
                .map(PublicReportServiceImpl::toResp)
                .toList();

        return new PublicReportResp(
                book.getName(),
                summary.month(),
                summary.income(),
                summary.expense(),
                summary.balance(),
                summary.totalBalance(),
                categories,
                transactions);
    }

    private static ReportTransactionResp toResp(TransactionBalanceRow row) {
        return new ReportTransactionResp(
                row.getTransDate(),
                row.getType(),
                StatisticCalculator.money(row.getAmount()),
                row.getNote(),
                row.getCategoryName(),
                row.getCategoryIcon(),
                row.getCategoryColor(),
                StatisticCalculator.money(row.getBalanceAfter()));
    }
}