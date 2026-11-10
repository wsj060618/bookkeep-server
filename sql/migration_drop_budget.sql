-- ============================================================
-- 迁移：删掉 budget 预算表（口径变更 2026-11-10）
--
-- 背景：预算早在 PLAN §2.4 就被移出范围，但表一直留在 schema.sql 里当"预留位"。
--       结果库里有个永远 0 行的表，读 schema 的人会以为有预算功能。
--       现在干脆删掉：真要恢复预算，按 §2.4 的设计重建表即可（一分钟的事）。
--
-- 安全前提：这张表从来没有代码引用（无实体 / 无 Mapper / 无接口），实测 0 行，也没有外键。
--
-- 一次性操作：重复执行无副作用（IF EXISTS）。
-- ============================================================

USE bookkeep;
SET NAMES utf8mb4;   -- 中文注释/数据必须按 UTF-8 解释，否则会双重编码（见 migration_fix_mojibake_comments.sql）

DROP TABLE IF EXISTS `budget`;

-- 复查：应只剩 9 张表
SELECT TABLE_NAME, TABLE_ROWS
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'bookkeep'
ORDER BY TABLE_NAME;
