-- ============================================================
-- 迁移：把账本的分类挂回它来自的模板条目（category.template_item_id）
--
-- 为什么需要：从本版起「保存模板」会把改动同步进用过这套模板的账本
--   （新增条目 → 账本多一个分类；改名 → 账本里的分类跟着改名；删除 → 没有流水才删）。
--   同步必须知道「账本里这个分类是模板里哪一条」，否则改名会被当成「删旧 + 建新」，
--   一遇到流水就会被拦下，用户改不动名字。
--
-- 三件事：
--   1) category 加 template_item_id（NULL = 用户自建，不参与同步）
--   2) 历史账本 book.category_template_id IS NULL 的，若该账号只有一套模板就指回它
--      （有 2 套以上就猜不准，宁可不动——这类账本不参与同步，也不会出错）
--   3) 按「同账本 + 同方向 + 同名」把已有分类挂到对应模板条目上
--
-- 执行：mysql -uroot -p --default-character-set=utf8mb4 -e "source .../migration_template_item_link.sql"
-- 一次性操作（ALTER 不幂等；后两条 UPDATE 重跑无副作用）。
-- ============================================================

USE bookkeep;
SET NAMES utf8mb4;

-- ---------- 1) 加列 ----------
ALTER TABLE `category`
  ADD COLUMN `template_item_id` BIGINT NULL
      COMMENT '来自哪个模板条目 category_template_item.id；NULL = 用户自建（记一笔里新建）' AFTER `type`,
  ADD KEY `idx_category_template_item` (`template_item_id`);

-- ---------- 2) 老账本指回它那套模板（仅账号恰好一套模板时） ----------
UPDATE `book` b
SET b.category_template_id = (
  SELECT MIN(t.id) FROM `category_template` t WHERE t.user_id = b.user_id
)
WHERE b.deleted = 0
  AND b.category_template_id IS NULL
  AND (SELECT COUNT(*) FROM `category_template` t WHERE t.user_id = b.user_id) = 1;

-- ---------- 3) 分类挂回模板条目 ----------
UPDATE `category` c
JOIN `book` b ON b.id = c.book_id AND b.deleted = 0
JOIN `category_template_item` i
  ON i.template_id = b.category_template_id
 AND i.type = c.type
 AND i.name = c.name
SET c.template_item_id = i.id
WHERE c.deleted = 0
  AND c.template_item_id IS NULL;

-- 复查：还挂着 NULL 的分类 = 用户自建或该账本没有模板指针
SELECT b.id AS book_id, b.name AS book_name, b.category_template_id,
       SUM(c.template_item_id IS NULL) AS unlinked_categories,
       COUNT(*) AS total_categories
FROM `book` b
LEFT JOIN `category` c ON c.book_id = b.id AND c.deleted = 0
WHERE b.deleted = 0
GROUP BY b.id, b.name, b.category_template_id
ORDER BY b.id;