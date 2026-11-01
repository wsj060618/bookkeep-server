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
import com.bookkeep.service.constant.CategoryTemplate;
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
 * <p>为什么不 join category 查列表：过滤条件（type / category_id / 日期 / 备注）全在 {@code transaction} 表上，
 * 用 MyBatis-Plus 的 Wrapper 能自动带上 {@code deleted = 0}（手写 join 就得自己拼）；
 * 分类名称再按 id 批量补一次（一次 IN 查询），比每行一次 join 更可控、也更好读。
 *
 * <p><strong>这个类只守三条不变量</strong>，读代码时可以把每个 requireOwned* / check* 都归到其中一条：
 * <ol>
 *   <li><b>不越权</b>：任何操作先取 bookId（来自 token），任何查询都带 {@code book_id} 条件；</li>
 *   <li><b>方向不矛盾</b>：流水的 type 必须与所选分类的 type 一致（否则统计和余额都会算错）；</li>
 *   <li><b>钱不会变负</b>：支出不能超过余额；修改时要先把这条流水自己从余额里排除再判断。</li>
 * </ol>
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

        // 流水只存了 category_id，页面却要显示「聚餐」和它的颜色：
        // 这里一次性把本页涉及的分类查回来（一次 IN），再按 id 组装，避免每行查一次的 N+1。
        // 分类被删掉时 categoryMap.get() 会返回 null，TransactionResp.from 里会兜底成「已删除分类」。
        Map<Long, Category> categoryMap = loadCategories(result.getRecords());
        List<TransactionResp> list = result.getRecords().stream()
                .map(t -> TransactionResp.from(t, categoryMap.get(t.getCategoryId())))
                .toList();

        // 只回传 total + list，不把 MyBatis-Plus 的 IPage 暴露给前端（框架类型不进 API 契约）
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
        // 存「分类的 type」而不是前端传的：上面已校验两者相同，但落库以分类为准，
        // 保证即使校验逻辑将来被改动，库里的数据也不会自相矛盾
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
        // 先确认这条流水属于当前账本，别人的 id 一律 404（不暴露"存在但你没权限"）
        requireOwnedTransaction(id, bookId);

        // 逻辑删除（@TableLogic → UPDATE deleted=1），三处连带效果自动成立：
        //   ① 列表查不到（Wrapper 自动拼 deleted=0）
        //   ② 余额随之恢复（TransactionMapper.xml 里手写了 deleted=0）
        //   ③ 分类的"引用计数"也会减少（countByCategoryId 同样带 deleted=0）
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
     * 余额校验：支出金额不能超过「改完之后」的余额。
     *
     * <p><b>为什么要有 {@code excludeTransactionId}</b>：余额是用 SUM 现算的，不存字段。
     * 设 R = 除这一笔之外其他流水的净额，E_new = 新金额：
     * <pre>
     *   改完后的余额 = R − E_new ≥ 0  ⟺  E_new ≤ R
     *   而 sumBalance(bookId) 得到的是 R − E_old（旧金额还占着位置），不能直接拿来比。
     * </pre>
     * 例：收入 1000、旧支出 900（余额 100），把 900 改成 950 是合法的（改完 50），
     * 但拿"当前余额 100"去比，950 &gt; 100 会被误判超支；连"只改备注"（金额没变）
     * 都会因为 900 &gt; 100 被拦。所以修改场景必须先把自己从余额里剔掉。
     *
     * <p><b>为什么是"先算后写"而不是"先写库再查余额、负数就回滚"</b>：
     * 后者靠异常撤销写入，会白白消耗自增 id、让后续副作用（日志/通知）提前发生，
     * 判断和写入也混在一起。这里只查一次、不产生任何写入，通过了才落库。
     *
     * <p>不做并发控制：本系统是单用户自用（生活委员一个人记），不存在两个人同时记账
     * 把余额刷成负数的场景；真要多用户共享账本，得在这里加行锁。
     *
     * @param excludeTransactionId 非 null 时把这笔流水从余额里排除（修改场景）；新增/查询传 null
     */
    private void checkBalance(Long bookId, BigDecimal amount, Long excludeTransactionId) {
        // 三元只执行命中的那个分支：只有一个分支会真的发 SQL
        BigDecimal balance = excludeTransactionId == null
                ? nonNull(transactionMapper.sumBalance(bookId))                              // 当前余额
                : nonNull(transactionMapper.sumBalanceExcluding(bookId, excludeTransactionId)); // 改完后的基准 R
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
                && !CategoryTemplate.TYPE_INCOME.equals(type)
                && !CategoryTemplate.TYPE_EXPENSE.equals(type)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "收支类型只能是 income 或 expense");
        }
    }

    private boolean isExpense(Category category) {
        return CategoryTemplate.TYPE_EXPENSE.equals(category.getType());
    }

    /** 取「当前账本里的这个分类」；不存在或属于别人的账本，一律 404（不暴露存在性）。 */
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
            // 空列表直接返回，避免拼出 `IN ()` 这种非法 SQL（第一页没数据时就会走到）
            return Map.of();
        }
        return categoryMapper.selectBatchIds(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
    }
}