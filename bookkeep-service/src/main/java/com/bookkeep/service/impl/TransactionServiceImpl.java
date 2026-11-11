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
import com.bookkeep.service.mapper.TransactionReceiptMapper;
import com.bookkeep.service.mapper.row.ReceiptCountRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 流水业务。两条不变量：不越权（查询必带 book_id）、方向与分类一致。
 * <p>余额可以为负，不做「支出不能超过余额」的拦截——删错一笔收入就能把余额打成负数，
 * 拦支出只会让人连修数据都做不到。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionServiceImpl implements TransactionService {

    private final TransactionMapper transactionMapper;
    private final CategoryMapper categoryMapper;
    private final TransactionReceiptMapper transactionReceiptMapper;
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
        Map<Long, Integer> receiptCounts = loadReceiptCounts(result.getRecords());
        List<TransactionResp> list = result.getRecords().stream()
                .map(t -> TransactionResp.from(t, categoryMap.get(t.getCategoryId()),
                        receiptCounts.getOrDefault(t.getId(), 0)))
                .toList();

        return new PageResult<>(result.getTotal(), list);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TransactionResp create(TransactionReq req) {
        // 写操作：归档账本只读
        Long bookId = currentBookService.writableBookId();
        Category category = requireOwnedCategory(req.categoryId(), bookId);
        checkTypeMatchesCategory(req.type(), category);

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
        Long bookId = currentBookService.writableBookId();
        requireOwnedTransaction(id, bookId);
        Category category = requireOwnedCategory(req.categoryId(), bookId);
        checkTypeMatchesCategory(req.type(), category);

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
        return TransactionResp.from(updated, category,
                (int) transactionReceiptMapper.countByTransaction(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Long bookId = currentBookService.writableBookId();
        requireOwnedTransaction(id, bookId);
        // 逻辑删除：列表、余额、分类引用计数都会自动排除已删行
        transactionMapper.deleteById(id);
        log.info("删除流水：bookId={}, transactionId={}", bookId, id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteBatch(List<Long> ids) {
        Long bookId = currentBookService.writableBookId();
        List<Long> unique = ids.stream().distinct().toList();

        // 先数一遍「这些 id 里有多少属于本账本」：数量对不上就整批拒绝，绝不先删后报错
        long owned = transactionMapper.selectCount(Wrappers.<Transaction>query()
                .eq("book_id", bookId)
                .in("id", unique));
        if (owned < unique.size()) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "有 " + (unique.size() - owned) + " 笔流水不存在或已被删除，请刷新后重试");
        }

        // book_id 写进 WHERE：别人的流水即使 id 猜对了也删不到
        int deleted = transactionMapper.delete(Wrappers.<Transaction>query()
                .eq("book_id", bookId)
                .in("id", unique));
        log.info("批量删除流水：bookId={}, 请求 {} 条，实际删除 {} 条", bookId, unique.size(), deleted);
        return deleted;
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal balance() {
        return nonNull(transactionMapper.sumBalance(currentBookService.currentBookId()));
    }

    // ---------- 内部方法 ----------

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

    /** 一页流水的票据张数，同样一次 IN 查询（没有票据的流水不会出现在结果里，取默认 0） */
    private Map<Long, Integer> loadReceiptCounts(List<Transaction> transactions) {
        List<Long> ids = transactions.stream().map(Transaction::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return transactionReceiptMapper.countByTransactionIds(ids).stream()
                .collect(Collectors.toMap(ReceiptCountRow::getTransactionId,
                        row -> row.getCnt() == null ? 0 : row.getCnt().intValue()));
    }
}