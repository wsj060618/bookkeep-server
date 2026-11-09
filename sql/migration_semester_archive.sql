-- ============================================================
-- 迁移：学期归档（PLAN §10.5 方案 A）
--
-- 背景：班费天然按学期结算（秋季/春季）。做法是「一个学期 = 一本账本」：
--       给账本记一个学期截止日期，学期结束后「归档」——归档的账本只读：
--       不能再记流水、不能增删改分类，但仍可改名 / 改截止日期 / 取消归档 / 导出 / 公示。
--
-- 幂等性：MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，重复执行会报「Duplicate column」，
--         看到这个错说明已经迁移过了，忽略即可。
-- ============================================================

USE bookkeep;

ALTER TABLE book
  ADD COLUMN semester_end DATE NULL
  COMMENT '学期截止日期（可空）；只用于展示与提示，不影响任何统计口径';

ALTER TABLE book
  ADD COLUMN archived TINYINT NOT NULL DEFAULT 0
  COMMENT '学期归档：1 = 只读（仍可改名/改截止日期/取消归档/导出/公示）';

-- 复查
SELECT COUNT(*) AS books, SUM(archived) AS archived_books FROM book WHERE deleted = 0;