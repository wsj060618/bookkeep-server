package com.bookkeep.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.model.entity.Book;
import com.bookkeep.model.entity.Category;
import com.bookkeep.model.entity.CategoryTemplateItem;
import com.bookkeep.service.CategoryTemplateSyncService;
import com.bookkeep.service.mapper.BookMapper;
import com.bookkeep.service.mapper.CategoryMapper;
import com.bookkeep.service.mapper.TransactionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 模板条目 → 账本分类同步，口径见 {@link CategoryTemplateSyncService} */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryTemplateSyncServiceImpl implements CategoryTemplateSyncService {

    private final BookMapper bookMapper;
    private final CategoryMapper categoryMapper;
    private final TransactionMapper transactionMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncBooks(Long templateId, Long userId, List<CategoryTemplateItem> items) {
        List<Book> books = bookMapper.selectList(Wrappers.<Book>query()
                .eq("user_id", userId)
                .eq("category_template_id", templateId));
        for (Book book : books) {
            // 归档 = 只读：冻结的分类不跟着模板变，取消归档后下次保存模板会补齐
            if (book.getArchived() != null && book.getArchived() == 1) {
                continue;
            }
            syncBook(book, items);
        }
    }

    private void syncBook(Book book, List<CategoryTemplateItem> items) {
        List<Category> existing = categoryMapper.selectList(Wrappers.<Category>query()
                .eq("book_id", book.getId()));

        Map<Long, Category> linkedByItem = new LinkedHashMap<>();
        for (Category category : existing) {
            if (category.getTemplateItemId() != null) {
                linkedByItem.put(category.getTemplateItemId(), category);
            }
        }

        Set<Long> keptItemIds = new LinkedHashSet<>();
        for (CategoryTemplateItem item : items) {
            keptItemIds.add(item.getId());
            Category linked = linkedByItem.get(item.getId());
            if (linked == null) {
                insertFromItem(book, existing, item);
            } else {
                updateFromItem(book, existing, linked, item);
            }
        }

        // 模板里已经没有的条目：账本里对应的分类一起删，但碰了流水就整次失败
        for (Category category : existing) {
            Long itemId = category.getTemplateItemId();
            if (itemId == null || keptItemIds.contains(itemId)) {
                continue;
            }
            deleteWithGuard(book, category);
        }

        log.info("模板同步到账本：bookId={}, templateItemIds={}", book.getId(), keptItemIds);
    }

    private void insertFromItem(Book book, List<Category> existing, CategoryTemplateItem item) {
        String name = item.getName().trim();
        Category conflict = findByName(existing, item.getType(), name, null);
        if (conflict != null) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "账本「" + book.getName() + "」里已经有分类「" + name + "」，模板新增的同名条目没法同步；"
                            + "先把那个分类改名或删掉再保存模板");
        }
        Category category = new Category();
        category.setBookId(book.getId());
        category.setName(name);
        category.setType(item.getType());
        category.setIcon(iconOf(item.getIcon()));
        category.setColor(colorOf(item.getColor()));
        category.setSortOrder(item.getSortOrder());
        category.setTemplateItemId(item.getId());
        categoryMapper.insert(category);
        // 同一批里后续条目也要看到这一行，否则两个新条目同名时查不出冲突
        existing.add(category);
    }

    private void updateFromItem(Book book, List<Category> existing, Category linked, CategoryTemplateItem item) {
        String name = item.getName().trim();
        String icon = iconOf(item.getIcon());
        String color = colorOf(item.getColor());
        if (Objects.equals(linked.getName(), name)
                && Objects.equals(linked.getIcon(), icon)
                && Objects.equals(linked.getColor(), color)) {
            return;
        }
        if (!Objects.equals(linked.getName(), name)
                && findByName(existing, linked.getType(), name, linked.getId()) != null) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "账本「" + book.getName() + "」里已经有分类「" + name + "」，改不了名；先处理那个分类再保存模板");
        }
        // 只 set 会变的列：更新整行容易把 template_item_id / sort_order 覆盖成 null
        categoryMapper.update(null, Wrappers.<Category>update()
                .eq("id", linked.getId())
                .set("name", name)
                .set("icon", icon)
                .set("color", color));
        linked.setName(name);
        linked.setIcon(icon);
        linked.setColor(color);
    }

    private void deleteWithGuard(Book book, Category category) {
        long used = transactionMapper.countByCategoryId(category.getId());
        if (used > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "账本「" + book.getName() + "」的分类「" + category.getName() + "」下还有 " + used
                            + " 笔流水，不能从模板删掉；先把这些流水的分类改到别的分类");
        }
        categoryMapper.deleteById(category.getId());
    }

    private static Category findByName(List<Category> categories, String type, String name, Long excludeId) {
        for (Category category : categories) {
            if (Objects.equals(category.getId(), excludeId)) {
                continue;
            }
            if (Objects.equals(category.getType(), type) && Objects.equals(category.getName(), name)) {
                return category;
            }
        }
        return null;
    }

    private static String iconOf(String icon) {
        return StringUtils.hasText(icon) ? icon : "other";
    }

    private static String colorOf(String color) {
        return StringUtils.hasText(color) ? color : "gray";
    }
}