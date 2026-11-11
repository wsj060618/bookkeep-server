package com.bookkeep.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.model.dto.resp.CategoryStatResp;
import com.bookkeep.model.dto.resp.PublicReceiptResp;
import com.bookkeep.model.dto.resp.PublicReportResp;
import com.bookkeep.model.dto.resp.RangeSummaryResp;
import com.bookkeep.model.dto.resp.ReportTransactionResp;
import com.bookkeep.model.entity.Book;
import com.bookkeep.model.entity.ShareLink;
import com.bookkeep.model.entity.TransactionReceipt;
import com.bookkeep.service.PublicReportService;
import com.bookkeep.service.constant.CategoryPresets;
import com.bookkeep.service.mapper.BookMapper;
import com.bookkeep.service.mapper.TransactionMapper;
import com.bookkeep.service.mapper.TransactionReceiptMapper;
import com.bookkeep.service.mapper.row.TransactionBalanceRow;
import com.bookkeep.service.support.ShareLinkResolver;
import com.bookkeep.service.support.StatisticCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 公示页实现。
 * <p>账本来源是**链接**，不是 {@code CurrentBookService} / {@code SecurityUtil}：
 * 一条链接可能带多本账本，查不到（过期 / 从未分享 / 成员全被彻底删除）一律 404，
 * 绝不回落到「当前登录用户的账本」。
 * <p>{@code selectBatchIds} 是 MyBatis-Plus，会自动带 {@code deleted = 0}：
 * 进了回收站的账本在公示页里自然消失，不需要额外过滤。
 * <p>每笔流水附带票据（id + 标题）：图片内容走 {@code /api/public/report/{token}/receipts/{id}}，
 * 这条只读路径与登录态接口完全分开（PLAN §10.9）。
 */
@Service
@RequiredArgsConstructor
public class PublicReportServiceImpl implements PublicReportService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final BookMapper bookMapper;
    private final TransactionMapper transactionMapper;
    private final TransactionReceiptMapper receiptMapper;
    private final StatisticCalculator calculator;
    private final ShareLinkResolver shareLinkResolver;

    @Override
    @Transactional(readOnly = true)
    public PublicReportResp report(String shareToken, String startMonth, String endMonth) {
        // 过期 / 不存在都按同一个 404，不暴露「token 曾经存在过」（逻辑在 ShareLinkResolver 里只写一次）
        ShareLink link = shareLinkResolver.requireValid(shareToken);
        List<Long> bookIds = shareLinkResolver.bookIds(link.getId());

        // selectBatchIds 自然排除逻辑删除的账本；顺序按上面的勾选顺序还原
        Map<Long, Book> byId = bookMapper.selectBatchIds(bookIds).stream()
                .collect(Collectors.toMap(Book::getId, Function.identity()));
        List<Book> books = bookIds.stream().map(byId::get).filter(Objects::nonNull).toList();
        if (books.isEmpty()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "公示页不存在或已失效");
        }

        StatisticCalculator.MonthRange range = StatisticCalculator.resolveRange(startMonth, endMonth);
        List<PublicReportResp.BookReport> reports = new ArrayList<>(books.size());
        for (Book book : books) {
            reports.add(buildBookReport(book, range));
        }
        return new PublicReportResp(DATE_TIME.format(link.getExpiresAt()), reports);
    }

    private PublicReportResp.BookReport buildBookReport(Book book, StatisticCalculator.MonthRange range) {
        RangeSummaryResp summary = calculator.summaryBetween(book.getId(), range);
        // 收支两个方向都给：公示页要把「钱花哪了」和「钱从哪来」一起摆给同学看
        List<CategoryStatResp> expenseCategories = calculator.categoriesBetween(
                book.getId(), range, CategoryPresets.TYPE_EXPENSE);
        List<CategoryStatResp> incomeCategories = calculator.categoriesBetween(
                book.getId(), range, CategoryPresets.TYPE_INCOME);

        // 结余在 CTE 里按全账本累计、外层才按区间过滤，所以截出来的每一行结余仍然是对的
        List<TransactionBalanceRow> rows = transactionMapper
                .selectRangeWithRunningBalance(book.getId(), range.startDate(), range.endDate());
        Map<Long, List<TransactionReceipt>> receiptsByTransaction = loadReceipts(rows);

        List<ReportTransactionResp> transactions = rows.stream()
                .map(row -> toResp(row, receiptsByTransaction.getOrDefault(row.getId(), List.of())))
                .toList();

        return new PublicReportResp.BookReport(
                book.getId(),
                book.getName(),
                summary.startMonth(),
                summary.endMonth(),
                summary.income(),
                summary.expense(),
                summary.balance(),
                expenseCategories,
                incomeCategories,
                transactions);
    }

    /** 一次 IN 查询把本页的票据取回来，避免每行一次（N+1） */
    private Map<Long, List<TransactionReceipt>> loadReceipts(List<TransactionBalanceRow> rows) {
        List<Long> ids = rows.stream().map(TransactionBalanceRow::getId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return receiptMapper.selectByTransactionIds(ids).stream()
                .collect(Collectors.groupingBy(TransactionReceipt::getTransactionId));
    }

    private static ReportTransactionResp toResp(TransactionBalanceRow row, List<TransactionReceipt> receipts) {
        List<PublicReceiptResp> publicReceipts = receipts.stream().map(PublicReceiptResp::from).toList();
        return new ReportTransactionResp(
                row.getTransDate(),
                row.getType(),
                StatisticCalculator.money(row.getAmount()),
                row.getNote(),
                row.getCategoryName(),
                row.getCategoryIcon(),
                row.getCategoryColor(),
                StatisticCalculator.money(row.getBalanceAfter()),
                publicReceipts.size(),
                publicReceipts);
    }
}