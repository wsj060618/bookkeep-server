-- ============================================================
-- 迁移：book 增加「回收站时间」
-- 适用：已经用旧版 schema.sql 建过库的环境（新装直接用 schema.sql，不必执行本文件）
-- 说明：删除账本改为「进回收站」：deleted=1 表示已删，deleted_at 记录删除时刻；
--       满 15 天后由定时任务连同该账本的分类、流水彻底删除。
--       老数据里 deleted=1 的账本 deleted_at 为 NULL → 永远不会被自动清理，
--       需要的话手工补：UPDATE book SET deleted_at = NOW() WHERE deleted = 1;
-- ============================================================

USE bookkeep;
SET NAMES utf8mb4;   -- 中文注释/数据必须按 UTF-8 解释，否则会双重编码（见 migration_fix_mojibake_comments.sql）

ALTER TABLE book
    ADD COLUMN deleted_at DATETIME NULL
        COMMENT '进入回收站的时间（deleted=1 时才有值）；满 15 天连同分类/流水彻底删除'
        AFTER deleted;