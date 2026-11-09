package com.bookkeep.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.PageResult;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.model.dto.req.TransactionQuery;
import com.bookkeep.model.dto.req.TransactionReq;
import com.bookkeep.model.dto.resp.TransactionResp;
import com.bookkeep.model.entity.Category;
import com.bookkeep.model.entity.Transaction;
import com.bookkeep.service.CurrentBookService;
import com.bookkeep.service.TransactionService;
import com.bookkeep.service.constant.CategoryPresets;
import com.bookkeep.service.mapper.CategoryMapper;
import com.bookkeep.service.mapper.TransactionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 流水业务实现。
 * <p>三条不变量：不越权（查询必带 book_id）、方向一致（流水 type = 分类 type）、支出不超余额。
 * <p>列表不 join category：过滤条件都在 transaction 表上，Wrapper 能白拿 {@code deleted = 0}；
 * 分类名再按 id 批量补一次（避免 N+1）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionServiceImpl implements TransactionService {

    private final TransactionMapper transactionMapper;
    private final CategoryMapper categoryMapper;
    private final CurrentBookService currentBookService;

    @Override
    @Transactional(readOnly = true)
    public PageResult<TransactionResp> page(TransactionQuery query) {
        Long bookId = currentBookService.currentBookId();
        validateType(query.getType());

        // 先把关键字 trim 出来再判空：`.like(条件, "note", query.getKeyword().trim())` 的第三个参数
        // 会**先求值**（条件为 false 也照样执行），keyword 为 null 时就 NPE。
        // 这类「看似有条件保护、其实参数已经算过」的写法，是 Wrapper 里最常见的坑。
        String keyword = StringUtils.hasText(query.getKeyword()) ? query.getKeyword().trim() : null;

        var wrapper = Wrappers.<Transaction>query()
                .eq("book_id", bookId)
                .eq(StringUtils.hasText(query.getType()), "type", query.getType())
                .eq(query.getCategoryId() != null, "category_id", query.getCategoryId())
                .ge(query.getStartDate() != null, "trans_date", query.getStartDate())
                .le(query.getEndDate() != null, "trans_date", query.getEndDate())
                .like(keyword != null, "note", keyword)
                // 同一天多笔要按 id 兜底排序，否则分页时顺序不稳定（同一行可能重复出现或漏掉）
                .orderByDesc("trans_date", "id");

        IPage<Transaction> result = transactionMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()), wrapper);

        Map<Long, Category> categoryMap = loadCategories(result.getRecords());
        List<TransactionResp> list = result.getRecords().stream()
                .map(t -> TransactionResp.from(t, categoryMap.get(t.getCategoryId())))
                .toList();

        return new PageResult<>(result.getTotal(), list);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TransactionResp create(TransactionReq req) {
        Long bookId = currentBookService.currentBookId();
        Category category = requireOwnedCategory(req.categoryId(), bookId);
        checkTypeMatchesCategory(req.type(), category);

        if (isExpense(category)) {
            checkBalance(bookId, req.amount(), null); // 新增：按当前余额判断
        }

        Transaction transaction = new Transaction();
        transaction.setBookId(bookId);
        transaction.setCategoryId(category.getId());
        // 落库以分类为准，保证库里不会出现「支出分类 + 收入流水」这种矛盾数据
        transaction.setType(category.getType());
        transaction.setAmount(req.amount());
        transaction.setTransDate(req.transDate());
        transaction.setNote(StringUtils.hasText(req.note()) ? req.note().trim() : null);
        transactionMapper.insert(transaction);

        log.info("新增流水：bookId={}, transactionId={}, type={}, amount={}, date={}",
                bookId, transaction.getId(), transaction.getType(), transaction.getAmount(), transaction.getTransDate());
        return TransactionResp.from(transaction, category);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TransactionResp update(Long id, TransactionReq req) {
        Long bookId = currentBookService.currentBookId();
        requireOwnedTransaction(id, bookId);
        Category category = requireOwnedCategory(req.categoryId(), bookId);
        checkTypeMatchesCategory(req.type(), category);

        if (isExpense(category)) {
            // 排除自己再算余额：否则「把 100 改成 90」会因为原 100 还在余额里而误判超支
            checkBalance(bookId, req.amount(), id);
        }

        // 显式列更新：note 传空要真的清空（updateById 会忽略 null，清不掉）
        transactionMapper.update(null, Wrappers.<Transaction>update()
                .eq("id", id)
                .set("category_id", category.getId())
                .set("type", category.getType())
                .set("amount", req.amount())
                .set("trans_date", req.transDate())
                .set("note", StringUtils.hasText(req.note()) ? req.note().trim() : null));

        Transaction updated = transactionMapper.selectById(id);
        log.info("修改流水：bookId={}, transactionId={}, type={}, amount={}", bookId, id,
                updated.getType(), updated.getAmount());
        return TransactionResp.from(updated, category);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Long bookId = currentBookService.currentBookId();
        requireOwnedTransaction(id, bookId);
        // 逻辑删除：列表、余额、分类引用计数都会自动排除已删行
        transactionMapper.deleteById(id);
        log.info("删除流水：bookId={}, transactionId={}", bookId, id);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal balance() {
        return nonNull(transactionMapper.sumBalance(currentBookService.currentBookId()));
    }

    // ---------- 内部方法 ----------

    /**
     * 支出不能超过「改完之后」的余额。
     * <p>excludeTransactionId 是修改场景：设 R = 除本条外的净额，要求 新金额 ≤ R。
     * 不排除会把旧金额算两次——收入 1000、旧支出 900（余额 100）时，改成 950 会被误判超支，
     * 连只改备注都会被拦。
     */
    private void checkBalance(Long bookId, BigDecimal amount, Long excludeTransactionId) {
        // 三元只执行命中的分支，只有一个分支会真的发 SQL
        BigDecimal balance = excludeTransactionId == null
                ? nonNull(transactionMapper.sumBalance(bookId))
                : nonNull(transactionMapper.sumBalanceExcluding(bookId, excludeTransactionId));
        if (amount.compareTo(balance) > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "班费余额不足，当前余额 " + balance.setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
    }

    /** SUM 在空账本上返回 NULL，统一兜成 0，避免到处判空。 */
    private BigDecimal nonNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private void checkTypeMatchesCategory(String type, Category category) {
        validateType(type);
        if (!category.getType().equals(type)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "流水类型与所选分类不一致");
        }
    }

    private void validateType(String type) {
        if (StringUtils.hasText(type)
                && !CategoryPresets.TYPE_INCOME.equals(type)
                && !CategoryPresets.TYPE_EXPENSE.equals(type)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "收支类型只能是 income 或 expense");
        }
    }

    private boolean isExpense(Category category) {
        return CategoryPresets.TYPE_EXPENSE.equals(category.getType());
    }

    /** 取当前账本里的分类；不存在或属于别人，一律 404（不暴露存在性）。 */
    private Category requireOwnedCategory(Long categoryId, Long bookId) {
        Category category = categoryMapper.selectOne(Wrappers.<Category>query()
                .eq("id", categoryId)
                .eq("book_id", bookId));
        if (category == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "分类不存在");
        }
        return category;
    }

    private Transaction requireOwnedTransaction(Long id, Long bookId) {
        Transaction transaction = transactionMapper.selectOne(Wrappers.<Transaction>query()
                .eq("id", id)
                .eq("book_id", bookId));
        if (transaction == null) {
            // 别人的流水也走这里：不区分「不存在」和「不是你的」，避免探测 id
            throw new BusinessException(ResultCode.NOT_FOUND, "流水不存在");
        }
        return transaction;
    }

    /** 一次 IN 查询补齐本页的分类，避免 N+1。 */
    private Map<Long, Category> loadCategories(List<Transaction> transactions) {
        List<Long> categoryIds = transactions.stream()
                .map(Transaction::getCategoryId)
                .distinct()
                .toList();
        if (categoryIds.isEmpty()) {
            return Map.of();   // 空列表不能拼 IN ()
        }
        return categoryMapper.selectBatchIds(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
    }
}