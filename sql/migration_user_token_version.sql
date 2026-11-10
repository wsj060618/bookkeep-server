-- 给已有库补上「令牌版本」列（只跑一次；新库用 schema.sql 即可）。
-- 不跑这一列：MyBatis-Plus 生成的 SQL 会带 token_version，启动后所有用户查询直接报 Unknown column。
-- 语义：改密码 / --reset-password 时 +1；JWT 里带 ver，过滤器比对不一致就按未认证处理。
USE bookkeep;
SET NAMES utf8mb4;   -- 中文注释/数据必须按 UTF-8 解释，否则会双重编码（见 migration_fix_mojibake_comments.sql）

ALTER TABLE `user`
    ADD COLUMN token_version INT NOT NULL DEFAULT 0
        COMMENT '令牌版本：改密码/重置时 +1，旧 JWT 立即失效'
        AFTER password;