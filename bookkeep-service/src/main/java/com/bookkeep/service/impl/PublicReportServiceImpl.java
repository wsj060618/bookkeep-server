package com.bookkeep.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.model.dto.resp.CategoryStatResp;
import com.bookkeep.model.dto.resp.PublicReportResp;
import com.bookkeep.model.dto.resp.ReportTransactionResp;
import com.bookkeep.model.dto.resp.SummaryResp;
import com.bookkeep.model.entity.Book;
import com.bookkeep.model.entity.ShareLink;
import com.bookkeep.model.entity.ShareLinkBook;
import com.bookkeep.service.PublicReportService;
import com.bookkeep.service.constant.CategoryPresets;
import com.bookkeep.service.mapper.BookMapper;
import com.bookkeep.service.mapper.ShareLinkBookMapper;
import com.bookkeep.service.mapper.ShareLinkMapper;
import com.bookkeep.service.mapper.TransactionMapper;
import com.bookkeep.service.mapper.row.TransactionBalanceRow;
import com.bookkeep.service.support.StatisticCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 公示页实现。
 * <p>账本来源是**链接**，不是 {@code CurrentBookService} / {@code SecurityUtil}：
 * 一条链接可能带多本账本，查不到（过期 / 从未分享 / 成员全被彻底删除）一律 404，
 * 绝不回落到「当前登录用户的账本」。
 * <p>{@code selectBatchIds} 走 MyBatis-Plus，会自动拼 {@code deleted = 0}：
 * 进了回收站的账本在公示页里自然消失，不需要额外过滤。
 */
@Service
@RequiredArgsConstructor
public class PublicReportServiceImpl implements PublicReportService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ShareLinkMapper shareLinkMapper;
    private final ShareLinkBookMapper shareLinkBookMapper;
    private final BookMapper bookMapper;
    private final TransactionMapper transactionMapper;
    private final StatisticCalculator calculator;

    @Override
    @Transactional(readOnly = true)
    public PublicReportResp report(String shareToken, String month) {
        // 过期 / 不存在都按同一个 404，不暴露「token 曾经存在过」
        ShareLink link = shareLinkMapper.selectOne(Wrappers.<ShareLink>query()
                .eq("token", shareToken)
                .gt("expires_at", LocalDateTime.now()));
        if (link == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "公示页不存在或已失效");
        }

        List<Long> bookIds = shareLinkBookMapper.selectList(Wrappers.<ShareLinkBook>query()
                        .eq("share_link_id", link.getId())
                        .orderByAsc("sort_order", "id"))
                .stream()
                .map(ShareLinkBook::getBookId)
                .toList();
        if (bookIds.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "公示页不存在或已失效");
        }

        // selectBatchIds 自然排除逻辑删除的账本；顺序按上面的勾选顺序还原
        Map<Long, Book> byId = bookMapper.selectBatchIds(bookIds).stream()
                .collect(Collectors.toMap(Book::getId, Function.identity()));
        List<Book> books = bookIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
        if (books.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "公示页不存在或已失效");
        }

        YearMonth yearMonth = StatisticCalculator.resolveMonth(month);
        List<PublicReportResp.BookReport> reports = new ArrayList<>(books.size());
        for (Book book : books) {
            reports.add(buildBookReport(book, yearMonth));
        }
        return new PublicReportResp(DATE_TIME.format(link.getExpiresAt()), reports);
    }

    private PublicReportResp.BookReport buildBookReport(Book book, YearMonth yearMonth) {
        SummaryResp summary = calculator.summaryOf(book.getId(), yearMonth);
        // 收支两个方向都给：公示页要把「钱花哪了」和「钱从哪来」一起摆给同学看
        List<CategoryStatResp> expenseCategories = calculator.categoriesOf(
                book.getId(), yearMonth, CategoryPresets.TYPE_EXPENSE);
        List<CategoryStatResp> incomeCategories = calculator.categoriesOf(
                book.getId(), yearMonth, CategoryPresets.TYPE_INCOME);
        List<ReportTransactionResp> transactions = transactionMapper
                .selectMonthWithRunningBalance(book.getId(), summary.month())
                .stream()
                .map(PublicReportServiceImpl::toResp)
                .toList();

        return new PublicReportResp.BookReport(
                book.getId(),
                book.getName(),
                summary.month(),
                summary.income(),
                summary.expense(),
                summary.balance(),
                summary.totalBalance(),
                expenseCategories,
                incomeCategories,
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