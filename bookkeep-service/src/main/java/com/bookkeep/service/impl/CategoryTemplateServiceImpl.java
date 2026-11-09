package com.bookkeep.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.common.util.SecurityUtil;
import com.bookkeep.model.dto.req.CategoryTemplateReq;
import com.bookkeep.model.dto.resp.CategoryTemplateResp;
import com.bookkeep.model.entity.Book;
import com.bookkeep.model.entity.Category;
import com.bookkeep.model.entity.CategoryTemplate;
import com.bookkeep.model.entity.CategoryTemplateItem;
import com.bookkeep.model.entity.Transaction;
import com.bookkeep.service.CategoryTemplateService;
import com.bookkeep.service.CurrentBookService;
import com.bookkeep.service.constant.CategoryPresets;
import com.bookkeep.service.mapper.BookMapper;
import com.bookkeep.service.mapper.CategoryMapper;
import com.bookkeep.service.mapper.CategoryTemplateItemMapper;
import com.bookkeep.service.mapper.CategoryTemplateMapper;
import com.bookkeep.service.mapper.TransactionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 分类模板实现。
 * <p>三条规则：
 * <ol>
 *   <li>模板跟**账号**走（{@code SecurityUtil.getUserId()}），不按账本隔离；应用时才落到当前账本</li>
 *   <li>内置模板 id 固定 0、不入库，内容取 {@link CategoryPresets}——注册预置的分类与内置模板必须永远一致</li>
 *   <li>应用模板要求账本**没有任何流水**：换模板会把分类整套换掉，已有流水的分类归属就断了</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryTemplateServiceImpl implements CategoryTemplateService {

    private final CategoryTemplateMapper templateMapper;
    private final CategoryTemplateItemMapper itemMapper;
    private final CategoryMapper categoryMapper;
    private final BookMapper bookMapper;
    private final TransactionMapper transactionMapper;
    private final CurrentBookService currentBookService;

    @Override
    @Transactional(readOnly = true)
    public List<CategoryTemplateResp> list() {
        Long userId = SecurityUtil.getUserId();
        List<CategoryTemplateResp> result = new ArrayList<>();
        result.add(builtinResp());

        List<CategoryTemplate> templates = templateMapper.selectList(Wrappers.<CategoryTemplate>query()
                .eq("user_id", userId)
                .orderByAsc("id"));
        if (templates.isEmpty()) {
            return result;
        }

        // 一次查出这批模板的全部条目再分组，避免按模板逐个查（N+1）
        Map<Long, List<CategoryTemplateItem>> itemsByTemplate = new LinkedHashMap<>();
        List<CategoryTemplateItem> items = itemMapper.selectList(Wrappers.<CategoryTemplateItem>query()
                .in("template_id", templates.stream().map(CategoryTemplate::getId).toList())
                .orderByAsc("sort_order", "id"));
        for (CategoryTemplateItem item : items) {
            itemsByTemplate.computeIfAbsent(item.getTemplateId(), k -> new ArrayList<>()).add(item);
        }

        for (CategoryTemplate template : templates) {
            result.add(new CategoryTemplateResp(
                    template.getId(),
                    template.getName(),
                    false,
                    itemsByTemplate.getOrDefault(template.getId(), List.of()).stream()
                            .map(CategoryTemplateServiceImpl::toItem)
                            .toList()));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CategoryTemplateResp create(CategoryTemplateReq req) {
        Long userId = SecurityUtil.getUserId();
        Long bookId = currentBookService.currentBookId();
        String name = req.name().trim();
        requireNameAvailable(userId, name, null);

        List<Category> categories = categoryMapper.selectList(Wrappers.<Category>query()
                .eq("book_id", bookId)
                .orderByAsc("sort_order", "id"));
        if (categories.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前账本还没有分类，先建一个分类再存为模板");
        }

        CategoryTemplate template = new CategoryTemplate();
        template.setUserId(userId);
        template.setName(name);
        templateMapper.insert(template);

        List<CategoryTemplateResp.Item> items = new ArrayList<>(categories.size());
        for (Category category : categories) {
            CategoryTemplateItem item = new CategoryTemplateItem();
            item.setTemplateId(template.getId());
            item.setName(category.getName());
            item.setType(category.getType());
            item.setIcon(category.getIcon());
            item.setColor(category.getColor());
            item.setSortOrder(category.getSortOrder());
            itemMapper.insert(item);
            items.add(toItem(item));
        }

        log.info("从账本存模板：userId={}, bookId={}, templateId={}, name={}, items={}",
                userId, bookId, template.getId(), name, items.size());
        return new CategoryTemplateResp(template.getId(), name, false, items);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CategoryTemplateResp rename(Long id, CategoryTemplateReq req) {
        Long userId = SecurityUtil.getUserId();
        requireOwned(id, userId);
        String name = req.name().trim();
        requireNameAvailable(userId, name, id);

        templateMapper.update(null, Wrappers.<CategoryTemplate>update()
                .eq("id", id)
                .set("name", name));

        List<CategoryTemplateItem> items = itemMapper.selectList(Wrappers.<CategoryTemplateItem>query()
                .eq("template_id", id)
                .orderByAsc("sort_order", "id"));
        log.info("模板改名：userId={}, templateId={}, name={}", userId, id, name);
        return new CategoryTemplateResp(id, name, false,
                items.stream().map(CategoryTemplateServiceImpl::toItem).toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Long userId = SecurityUtil.getUserId();
        requireOwned(id, userId);

        itemMapper.delete(Wrappers.<CategoryTemplateItem>query().eq("template_id", id));
        templateMapper.deleteById(id);
        // 引用它的账本指回内置模板；回收站里的账本也要清，否则恢复后会指向一个不存在的模板
        bookMapper.clearTemplateRef(userId, id);
        log.info("删除模板：userId={}, templateId={}", userId, id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void apply(Long id) {
        Long userId = SecurityUtil.getUserId();
        Long bookId = currentBookService.currentBookId();
        List<CategoryTemplateResp.Item> items = resolveItems(id, userId);

        // 换模板 = 分类整套换掉，已有流水的分类归属会断 → 直接拒绝，绝不悄悄迁移流水
        Long used = transactionMapper.selectCount(Wrappers.<Transaction>query().eq("book_id", bookId));
        if (used != null && used > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "当前账本已有 " + used + " 笔流水，不能再换模板；请新建一个账本或先清空流水");
        }

        categoryMapper.delete(Wrappers.<Category>query().eq("book_id", bookId));
        for (CategoryTemplateResp.Item item : items) {
            Category category = new Category();
            category.setBookId(bookId);
            category.setName(item.name());
            category.setType(item.type());
            category.setIcon(StringUtils.hasText(item.icon()) ? item.icon() : "other");
            category.setColor(StringUtils.hasText(item.color()) ? item.color() : "gray");
            category.setSortOrder(item.sortOrder());
            categoryMapper.insert(category);
        }
        // 内置模板写 NULL（列注释：NULL = 内置），自定义模板写真实 id
        bookMapper.update(null, Wrappers.<Book>update()
                .eq("id", bookId)
                .set("category_template_id", id == CategoryPresets.BUILTIN_ID ? null : id));

        log.info("应用分类模板：userId={}, bookId={}, templateId={}, items={}", userId, bookId, id, items.size());
    }

    /* ---------- 内部 ---------- */

    private CategoryTemplateResp builtinResp() {
        List<CategoryTemplateResp.Item> items = CategoryPresets.classCategories().stream()
                .map(seed -> new CategoryTemplateResp.Item(
                        seed.name(), seed.type(), seed.icon(), seed.color(), seed.sortOrder()))
                .toList();
        return new CategoryTemplateResp(CategoryPresets.BUILTIN_ID, CategoryPresets.BUILTIN_NAME, true, items);
    }

    /** 取模板条目：id=0 走代码常量，其余必须是自己名下的模板。 */
    private List<CategoryTemplateResp.Item> resolveItems(Long id, Long userId) {
        if (Objects.equals(id, CategoryPresets.BUILTIN_ID)) {
            return builtinResp().items();
        }
        requireOwned(id, userId);
        return itemMapper.selectList(Wrappers.<CategoryTemplateItem>query()
                        .eq("template_id", id)
                        .orderByAsc("sort_order", "id"))
                .stream()
                .map(CategoryTemplateServiceImpl::toItem)
                .toList();
    }

    private CategoryTemplate requireOwned(Long id, Long userId) {
        // 内置模板只能应用，不能改名/删除
        if (Objects.equals(id, CategoryPresets.BUILTIN_ID)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "内置模板不能修改或删除，只能应用");
        }
        CategoryTemplate template = templateMapper.selectOne(Wrappers.<CategoryTemplate>query()
                .eq("id", id)
                .eq("user_id", userId));
        if (template == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "模板不存在");
        }
        return template;
    }

    /** 模板名同一用户内唯一：下拉里两条同名模板没法选。 */
    private void requireNameAvailable(Long userId, String name, Long excludeId) {
        Long dup = templateMapper.selectCount(Wrappers.<CategoryTemplate>query()
                .eq("user_id", userId)
                .eq("name", name)
                .ne(excludeId != null, "id", excludeId));
        if (dup != null && dup > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "已有同名模板「" + name + "」");
        }
    }

    private static CategoryTemplateResp.Item toItem(CategoryTemplateItem item) {
        return new CategoryTemplateResp.Item(
                item.getName(), item.getType(), item.getIcon(), item.getColor(), item.getSortOrder());
    }
}
