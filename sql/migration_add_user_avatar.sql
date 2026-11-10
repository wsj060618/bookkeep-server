-- ============================================================
-- 迁移：user 表增加头像字段
-- 适用：已经按旧版 schema.sql 建好库，不想重建的情况
-- 说明：执行一次即可；重复执行会报 Duplicate column name（MySQL 不支持 ADD COLUMN IF NOT EXISTS）
-- 全新安装请直接用 schema.sql，里面已包含 avatar
-- ============================================================

USE bookkeep;
SET NAMES utf8mb4;   -- 中文注释/数据必须按 UTF-8 解释，否则会双重编码（见 migration_fix_mojibake_comments.sql）

ALTER TABLE `user`
  ADD COLUMN avatar VARCHAR(500) NULL COMMENT '头像 URL，可空' AFTER email;
