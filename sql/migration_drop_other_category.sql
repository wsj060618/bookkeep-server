-- ============================================================
-- 迁移：清掉账本里预置的「其他」分类（口径变更 2026-11-10）
--
-- 背景：新口径里「其他」不再是预置分类，而是「记一笔」分类下拉末尾的虚拟入口
--       （选中即弹窗现建分类）。所以 2026-11-10 之前建的账本要补一刀，
--       否则下拉里会同时出现真的「其他」和「其他（新建分类…）」。
--
-- 安全前提：**只删没有任何流水引用的「其他」**。
--       有流水引用的一律保留——删了会让那些流水失去分类归属。
--       单据里用的是逻辑删除（deleted=1），与分类接口的删除语义一致。
--
-- 一次性操作：重复执行无副作用（已 deleted=1 的行不会再被选中）。
-- ============================================================

USE bookkeep;

UPDATE category c
SET c.deleted = 1,
    c.updated_at = NOW()
WHERE c.deleted = 0
  AND c.name = '其他'
  AND c.type IN ('expense', 'income')
  AND NOT EXISTS (SELECT 1 FROM `transaction` t WHERE t.category_id = c.id);

-- 复查：每个账本剩余的分类条数（新口径应为 11 条）
SELECT b.id AS book_id, b.name AS book_name, COUNT(c.id) AS live_categories
FROM book b
LEFT JOIN category c ON c.book_id = b.id AND c.deleted = 0
WHERE b.deleted = 0
GROUP BY b.id, b.name;