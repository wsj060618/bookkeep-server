-- ============================================================
-- 迁移：清掉 category_template_item 里的孤儿行（模板已删、条目还在）
--
-- 背景：模板删除 / 注销账户两条业务路径都会「先删条目再删模板」（见
--       CategoryTemplateServiceImpl#delete 与 BookMapper#hardDeleteTemplateItemsByUser），
--       所以正常使用不会产生孤儿行。这里的残留来自早期手工清理测试账号的 SQL——
--       直接删了 category_template 却漏了条目，留下 template_id 指向不存在的模板。
--
-- 影响：孤儿行不会被任何接口读到（列表按 template_id 关联查询），但会让
--       「category_template_item 里怎么一堆重复条目」这种困惑反复出现，所以清掉。
--
-- 一次性操作：可重复执行（第二次删 0 行）。
-- ============================================================

USE bookkeep;

-- 清理前：看看有多少残留
SELECT cti.template_id, COUNT(*) AS orphan_items
FROM category_template_item cti
WHERE NOT EXISTS (SELECT 1 FROM category_template ct WHERE ct.id = cti.template_id)
GROUP BY cti.template_id;

DELETE cti
FROM category_template_item cti
WHERE NOT EXISTS (SELECT 1 FROM category_template ct WHERE ct.id = cti.template_id);

-- 清理后复查：应只剩「每套模板一份条目」（3 套 × 11 条 ≈ 34 行）
SELECT COUNT(*) AS live_items FROM category_template_item;
