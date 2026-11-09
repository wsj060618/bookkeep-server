package com.bookkeep.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.model.dto.req.CategoryReq;
import com.bookkeep.model.dto.resp.CategoryResp;
import com.bookkeep.model.entity.Category;
import com.bookkeep.service.CategoryService;
import com.bookkeep.service.CurrentBookService;
import com.bookkeep.service.constant.CategoryPresets;
import com.bookkeep.service.mapper.CategoryMapper;
import com.bookkeep.service.mapper.TransactionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 分类业务实现。
 * <p>四条规则：
 * <ol>
 *   <li>所有读写都限定在当前账本内（{@link CurrentBookService}），拿别人的分类 id 一律「不存在」</li>
 *   <li>分类的收支类型建好后**不能改**：改了会让该分类下历史流水的方向失真</li>
 *   <li>同一账本、同一方向下分类名不重复，否则「记一笔」下拉里会出现两条一样的选项</li>
 *   <li>删除前检查流水引用，不级联删流水 —— 账目不能因为删一个分类就凭空少掉</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryServiceImpl implements CategoryService {

    private final CategoryMapper categoryMapper;
    private final TransactionMapper transactionMapper;
    private final CurrentBookService currentBookService;

    @Override
    @Transactional(readOnly = true)
    public List<CategoryResp> list(String type) {
        Long bookId = currentBookService.currentBookId();
        if (StringUtils.hasText(type) && !isValidType(type)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "收支类型只能是 income 或 expense");
        }
        return categoryMapper.selectList(Wrappers.<Category>query()
                        .eq("book_id", bookId)
                        .eq(StringUtils.hasText(type), "type", type)
                        .orderByAsc("sort_order", "id"))
                .stream()
                .map(CategoryResp::from)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CategoryResp create(CategoryReq req) {
        // 写操作：归档账本只读
        Long bookId = currentBookService.writableBookId();
        String name = req.name().trim();
        requireNameAvailable(bookId, req.type(), name, null);

        Category category = new Category();
        category.setBookId(bookId);
        category.setName(name);
        category.setType(req.type());
        category.setIcon(StringUtils.hasText(req.icon()) ? req.icon().trim() : "other");
        category.setColor(StringUtils.hasText(req.color()) ? req.color().trim() : "gray");
        // 排在现有分类之后：取当前最大值 +1。不写死 0，否则新分类会插到最前面
        category.setSortOrder(nextSortOrder(bookId));
        categoryMapper.insert(category);

        log.info("新增分类：bookId={}, categoryId={}, name={}, type={}",
                bookId, category.getId(), category.getName(), category.getType());
        return CategoryResp.from(category);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CategoryResp update(Long id, CategoryReq req) {
        Long bookId = currentBookService.writableBookId();
        Category category = requireOwnedCategory(id, bookId);

        if (!category.getType().equals(req.type())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分类的收支类型不能修改");
        }

        String name = req.name().trim();
        // 改名也要查重：否则「记一笔」下拉里会出现两条一模一样的选项
        requireNameAvailable(bookId, category.getType(), name, id);

        category.setName(name);
        if (StringUtils.hasText(req.icon())) {
            category.setIcon(req.icon().trim());
        }
        if (StringUtils.hasText(req.color())) {
            category.setColor(req.color().trim());
        }
        // 只更新会变的列：显式 set 而不是整体 updateById，避免把 sortOrder 等字段覆盖成 null
        categoryMapper.update(null, Wrappers.<Category>update()
                .eq("id", id)
                .set("name", category.getName())
                .set("icon", category.getIcon())
                .set("color", category.getColor()));

        log.info("修改分类：bookId={}, categoryId={}, name={}", bookId, id, category.getName());
        return CategoryResp.from(category);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Long bookId = currentBookService.writableBookId();
        requireOwnedCategory(id, bookId);

        long used = transactionMapper.countByCategoryId(id);
        if (used > 0) {
            // 提示里带条数：用户才知道该先删流水还是换个分类
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "该分类下还有 " + used + " 笔流水，先把流水改到别的分类再删");
        }
        categoryMapper.deleteById(id);
        log.info("删除分类：bookId={}, categoryId={}", bookId, id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteBatch(List<Long> ids) {
        Long bookId = currentBookService.writableBookId();
        List<Long> unique = ids.stream().distinct().toList();

        // 先整批检查「有没有流水引用」：一条被引用就整批拒绝，避免删了一半才发现
        for (Long id : unique) {
            Category category = requireOwnedCategory(id, bookId);
            long used = transactionMapper.countByCategoryId(id);
            if (used > 0) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "分类「" + category.getName() + "」下还有 " + used + " 笔流水，先把流水改到别的分类再删");
            }
        }
        int deleted = categoryMapper.delete(Wrappers.<Category>query()
                .eq("book_id", bookId)
                .in("id", unique));
        log.info("批量删除分类：bookId={}, 请求 {} 个，实际删除 {} 个", bookId, unique.size(), deleted);
        return deleted;
    }

    /** 取「当前账本里的这个分类」，不存在或属于别人一律 404 语义。 */
    private Category requireOwnedCategory(Long id, Long bookId) {
        Category category = categoryMapper.selectOne(Wrappers.<Category>query()
                .eq("id", id)
                .eq("book_id", bookId));
        if (category == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "分类不存在");
        }
        return category;
    }

    /** 同一账本、同一收支方向下不允许重名：否则「记一笔」下拉里两条一样的选项分不清。 */
    private void requireNameAvailable(Long bookId, String type, String name, Long excludeId) {
        Category dup = categoryMapper.selectOne(Wrappers.<Category>query()
                .eq("book_id", bookId)
                .eq("type", type)
                .eq("name", name)
                .ne(excludeId != null, "id", excludeId)
                .last("LIMIT 1"));
        if (dup != null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "同类下已有同名分类「" + name + "」");
        }
    }

    private int nextSortOrder(Long bookId) {
        Category last = categoryMapper.selectOne(Wrappers.<Category>query()
                .eq("book_id", bookId)
                .orderByDesc("sort_order")
                .last("LIMIT 1"));
        return last == null || last.getSortOrder() == null ? 1 : last.getSortOrder() + 1;
    }

    private boolean isValidType(String type) {
        return CategoryPresets.TYPE_INCOME.equals(type) || CategoryPresets.TYPE_EXPENSE.equals(type);
    }
}