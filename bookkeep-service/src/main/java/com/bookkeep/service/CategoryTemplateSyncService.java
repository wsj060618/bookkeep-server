package com.bookkeep.service;

import com.bookkeep.model.entity.CategoryTemplateItem;

import java.util.List;

/**
 * 模板条目 → 账本分类的单向同步。
 *
 * <p>「保存模板」时把最新条目推进**用过这套模板**的账本（`book.category_template_id`）：
 * <ul>
 *   <li>新条目 → 账本里补一个分类（`category.template_item_id` 记着来源）</li>
 *   <li>条目改名/换图标 → 账本里那条分类跟着改，**流水仍挂在同一个 category.id 上，不会丢**</li>
 *   <li>条目被删 → 账本里对应的分类也删；**有流水就整次保存失败**（提示哪本账本、哪个分类、几笔流水）</li>
 * </ul>
 *
 * <p>归档账本（只读）跳过：它的分类是冻结的，不应该被模板改动动到。
 * 用户自建的分类（`template_item_id IS NULL`）永远不碰。
 */
public interface CategoryTemplateSyncService {

    /**
     * 把模板最新条目同步进所有引用它的账本。
     * <p>被流水挡住时抛 {@code BusinessException(BAD_REQUEST)}：调用方在事务里，模板条目也会一起回滚。
     *
     * @param templateId 模板 id
     * @param userId     模板归属账号（顺带把范围限定在自己账本，绝不跨账号）
     * @param items      模板保存后的最新条目（含 id）
     */
    void syncBooks(Long templateId, Long userId, List<CategoryTemplateItem> items);
}