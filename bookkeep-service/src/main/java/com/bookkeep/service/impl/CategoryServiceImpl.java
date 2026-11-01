package com.bookkeep.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.model.dto.req.CategoryReq;
import com.bookkeep.model.dto.resp.CategoryResp;
import com.bookkeep.model.entity.Category;
import com.bookkeep.service.CategoryService;
import com.bookkeep.service.CurrentBookService;
import com.bookkeep.service.constant.CategoryTemplate;
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
 * <p>三条规则：
 * <ol>
 *   <li>所有读写都限定在当前账本内（{@link CurrentBookService}），拿别人的分类 id 一律「不存在」</li>
 *   <li>分类的收支类型建好后**不能改**：改了会让该分类下历史流水的方向失真</li>
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
        Long bookId = currentBookService.currentBookId();

        Category category = new Category();
        category.setBookId(bookId);
        category.setName(req.name().trim());
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
        Long bookId = currentBookService.currentBookId();
        Category category = requireOwnedCategory(id, bookId);

        if (!category.getType().equals(req.type())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "分类的收支类型不能修改");
        }

        category.setName(req.name().trim());
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
        Long bookId = currentBookService.currentBookId();
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

    private int nextSortOrder(Long bookId) {
        Category last = categoryMapper.selectOne(Wrappers.<Category>query()
                .eq("book_id", bookId)
                .orderByDesc("sort_order")
                .last("LIMIT 1"));
        return last == null || last.getSortOrder() == null ? 1 : last.getSortOrder() + 1;
    }

    private boolean isValidType(String type) {
        return CategoryTemplate.TYPE_INCOME.equals(type) || CategoryTemplate.TYPE_EXPENSE.equals(type);
    }
}