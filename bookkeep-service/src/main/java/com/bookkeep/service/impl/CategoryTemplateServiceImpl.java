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
import com.bookkeep.service.CategoryTemplateSyncService;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 分类模板。模板跟账号走，应用/新建账本时才落到账本；
 * 应用模板要求账本没有流水（换模板会整套换掉分类）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryTemplateServiceImpl implements CategoryTemplateService {

    /** 一套模板最多 100 条 */
    private static final int MAX_ITEMS = 100;

    private final CategoryTemplateMapper templateMapper;
    private final CategoryTemplateItemMapper itemMapper;
    private final CategoryMapper categoryMapper;
    private final BookMapper bookMapper;
    private final TransactionMapper transactionMapper;
    private final CurrentBookService currentBookService;
    private final CategoryTemplateSyncService templateSyncService;

    @Override
    @Transactional(readOnly = true)
    public List<CategoryTemplateResp> list() {
        Long userId = SecurityUtil.getUserId();
        List<CategoryTemplateResp> result = new ArrayList<>();

        List<CategoryTemplate> templates = templateMapper.selectList(Wrappers.<CategoryTemplate>query()
                .eq("user_id", userId)
                .orderByAsc("id"));
        if (templates.isEmpty()) {
            // 兜底：注册时一定会写一套真实模板（见 AuthServiceImpl 与 DefaultTemplateInitializer），
            // 走到这里说明是历史账号且模板被全删了——继续给一个只读的内置投影，页面不至于空
            result.add(builtinResp());
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
        String name = req.name().trim();
        requireNameAvailable(userId, name, null);
        List<CategoryTemplateReq.Item> items = requireItems(req.items());

        CategoryTemplate template = new CategoryTemplate();
        template.setUserId(userId);
        template.setName(name);
        templateMapper.insert(template);

        List<CategoryTemplateItem> saved = insertItems(template.getId(), items);
        log.info("新建模板：userId={}, templateId={}, name={}, items={}",
                userId, template.getId(), name, saved.size());
        return new CategoryTemplateResp(template.getId(), name, false,
                saved.stream().map(CategoryTemplateServiceImpl::toItem).toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CategoryTemplateResp createFromBook(CategoryTemplateReq req) {
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
    public CategoryTemplateResp update(Long id, CategoryTemplateReq req) {
        Long userId = SecurityUtil.getUserId();
        requireOwned(id, userId);
        String name = req.name().trim();
        requireNameAvailable(userId, name, id);

        templateMapper.update(null, Wrappers.<CategoryTemplate>update()
                .eq("id", id)
                .set("name", name));

        List<CategoryTemplateItem> items;
        if (req.items() == null) {
            // 只改名：条目原样保留，账本也不用动
            items = loadItems(id);
        } else {
            List<CategoryTemplateReq.Item> wanted = requireItems(req.items());
            // 增量替换而不是删了重插：id 必须保留，否则「改名」会退化成「删旧建新」，
            // 一碰到流水就被拦住，用户永远改不动名字
            items = replaceItems(id, wanted);
            // 同一事务里推进账本：任何一本账本被流水挡住 → 模板改动也一起回滚
            templateSyncService.syncBooks(id, userId, items);
        }

        log.info("修改模板：userId={}, templateId={}, name={}, items={}", userId, id, name, items.size());
        return new CategoryTemplateResp(id, name, false,
                items.stream().map(CategoryTemplateServiceImpl::toItem).toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Long userId = SecurityUtil.getUserId();
        requireOwned(id, userId);

        // 账本侧不再跟这套模板走，先把分类上的来源标记清掉，避免留下指向不存在条目的 id
        categoryMapper.clearTemplateItemLinks(id);
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
        // 换模板会重写本账本的分类：归档账本只读
        Long bookId = currentBookService.writableBookId();
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
            category.setTemplateItemId(item.id());
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

    @Override
    public List<CategoryTemplateResp.Item> itemsOf(Long templateId) {
        Long resolved = templateId == null ? CategoryPresets.BUILTIN_ID : templateId;
        return resolveItems(resolved, SecurityUtil.getUserId());
    }

    /* ---------- 内部 ---------- */

    private CategoryTemplateResp builtinResp() {
        List<CategoryTemplateResp.Item> items = CategoryPresets.classCategories().stream()
                .map(seed -> new CategoryTemplateResp.Item(
                        null, seed.name(), seed.type(), seed.icon(), seed.color(), seed.sortOrder()))
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

    /** 条目至少要有一条，且方向只能是 income / expense；重名按方向分别判（支出、收入各一条同名不算冲突）。 */
    private List<CategoryTemplateReq.Item> requireItems(List<CategoryTemplateReq.Item> items) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "模板至少要有 1 个分类");
        }
        if (items.size() > MAX_ITEMS) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "一套模板最多 " + MAX_ITEMS + " 个分类");
        }
        Set<String> seen = new HashSet<>();
        Set<Long> seenIds = new HashSet<>();
        for (CategoryTemplateReq.Item item : items) {
            String type = item.type();
            if (!CategoryPresets.TYPE_INCOME.equals(type) && !CategoryPresets.TYPE_EXPENSE.equals(type)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "分类方向只能是收入或支出");
            }
            String name = item.name() == null ? "" : item.name().trim();
            if (name.isEmpty()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "分类名不能为空");
            }
            if (!seen.add(type + "|" + name)) {
                throw new BusinessException(ResultCode.BAD_REQUEST,
                        "同一方向下分类名不能重复：「" + name + "」");
            }
            if (item.id() != null && !seenIds.add(item.id())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "同一条分类在请求里出现了两次");
            }
        }
        return items;
    }

    /** sort_order 按传入顺序自增（前端先排支出再排收入），保证展示顺序稳定。 */
    private List<CategoryTemplateItem> insertItems(Long templateId, List<CategoryTemplateReq.Item> items) {
        List<CategoryTemplateItem> saved = new ArrayList<>(items.size());
        int order = 1;
        for (CategoryTemplateReq.Item item : items) {
            CategoryTemplateItem entity = new CategoryTemplateItem();
            entity.setTemplateId(templateId);
            entity.setName(item.name().trim());
            entity.setType(item.type());
            entity.setIcon(StringUtils.hasText(item.icon()) ? item.icon() : "other");
            entity.setColor(StringUtils.hasText(item.color()) ? item.color() : "gray");
            entity.setSortOrder(order++);
            itemMapper.insert(entity);
            saved.add(entity);
        }
        return saved;
    }

    private List<CategoryTemplateItem> loadItems(Long templateId) {
        return itemMapper.selectList(Wrappers.<CategoryTemplateItem>query()
                .eq("template_id", templateId)
                .orderByAsc("sort_order", "id"));
    }

    /**
     * 增量替换条目：带 id 的就地改、没 id 的新建、这次没出现的删掉。
     * <p>**id 必须留住**：账本分类靠 `template_item_id` 认亲，删了重插会让所有分类变成孤儿，
     * 下一次同步就会重复插入同名分类。
     */
    private List<CategoryTemplateItem> replaceItems(Long templateId, List<CategoryTemplateReq.Item> wanted) {
        Map<Long, CategoryTemplateItem> remaining = new LinkedHashMap<>();
        for (CategoryTemplateItem existing : loadItems(templateId)) {
            remaining.put(existing.getId(), existing);
        }

        List<CategoryTemplateItem> saved = new ArrayList<>(wanted.size());
        int order = 1;
        for (CategoryTemplateReq.Item item : wanted) {
            CategoryTemplateItem entity;
            if (item.id() == null) {
                entity = new CategoryTemplateItem();
                entity.setTemplateId(templateId);
            } else {
                entity = remaining.remove(item.id());
                if (entity == null) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "模板条目不存在或不属于这套模板");
                }
                if (!Objects.equals(entity.getType(), item.type())) {
                    throw new BusinessException(ResultCode.BAD_REQUEST,
                            "分类「" + entity.getName() + "」的收支方向不能改；改方向会让已记的流水对不上");
                }
            }
            entity.setName(item.name().trim());
            entity.setType(item.type());
            entity.setIcon(StringUtils.hasText(item.icon()) ? item.icon() : "other");
            entity.setColor(StringUtils.hasText(item.color()) ? item.color() : "gray");
            entity.setSortOrder(order++);
            if (item.id() == null) {
                itemMapper.insert(entity);
            } else {
                itemMapper.updateById(entity);
            }
            saved.add(entity);
        }

        for (CategoryTemplateItem removed : remaining.values()) {
            itemMapper.deleteById(removed.getId());
        }
        return saved;
    }

    private static CategoryTemplateResp.Item toItem(CategoryTemplateItem item) {
        return new CategoryTemplateResp.Item(
                item.getId(), item.getName(), item.getType(), item.getIcon(), item.getColor(), item.getSortOrder());
    }
}