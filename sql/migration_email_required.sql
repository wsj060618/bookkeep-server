-- 邮箱改为必填（只跑一次；新库用 schema.sql 即可）。
-- ⚠️ 破坏性：会**硬删除**所有 email 为 NULL 的账号，连同其账本 / 分类 / 流水。
--    本项目的两个老账号（wsj060618、demo3ban）都没有邮箱，按定稿口径直接清理。
--    执行前请先 mysqldump 备份（deploy/backup/before-email-*.sql）。
USE bookkeep;
SET NAMES utf8mb4;   -- 中文注释/数据必须按 UTF-8 解释，否则会双重编码（见 migration_fix_mojibake_comments.sql）

-- 1) 先清子表再清父表，避免留下孤儿数据
DELETE FROM `transaction`
WHERE book_id IN (
    SELECT id FROM book WHERE user_id IN (SELECT id FROM user WHERE email IS NULL)
);

DELETE FROM category
WHERE book_id IN (
    SELECT id FROM book WHERE user_id IN (SELECT id FROM user WHERE email IS NULL)
);

DELETE FROM book
WHERE user_id IN (SELECT id FROM user WHERE email IS NULL);

DELETE FROM user WHERE email IS NULL;

-- 2) 列改为必填（没有唯一索引：同一邮箱可以挂多个账号，找回密码靠 username + email 同时匹配）
ALTER TABLE `user`
    MODIFY COLUMN email VARCHAR(100) NOT NULL COMMENT '邮箱，必填：找回密码的唯一通道';

-- 3) 邮箱一次性 token（找回密码用；库里只存 sha256，不存明文）
CREATE TABLE IF NOT EXISTS password_reset_token (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT      NOT NULL COMMENT 'user.id',
    token_hash CHAR(64)    NOT NULL COMMENT 'sha256(明文 token)，库里不存明文',
    expires_at DATETIME    NOT NULL COMMENT '过期时间：签发后 15 分钟',
    used_at    DATETIME    NULL COMMENT '使用时间；非空即已作废',
    created_at DATETIME    DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_prt_token_hash (token_hash),
    KEY idx_prt_user (user_id)
) COMMENT='找回密码一次性 token';