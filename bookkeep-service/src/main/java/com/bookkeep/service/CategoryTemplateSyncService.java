package com.bookkeep.service;

import com.bookkeep.model.entity.CategoryTemplateItem;

import java.util.List;

/**
 * 模板条目 → 账本分类的单向同步。
 * <p>只作用于用过这套模板的账本：新增补分类、改名改分类（流水不丢）、
 * 删除要求该分类没有流水（否则整次保存失败）。归档账本和用户自建的分类不动。
 */
public interface CategoryTemplateSyncService {

    /** 被流水挡住时抛 400，调用方在事务里，模板改动一并回滚 */
    void syncBooks(Long templateId, Long userId, List<CategoryTemplateItem> items);
}