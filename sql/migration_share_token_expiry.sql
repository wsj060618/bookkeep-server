-- 公示链接改成「3 天有效、每次分享重新生成」（只跑一次；新库用 schema.sql 即可）。
-- 不跑这一列：Book 实体多一个字段，所有 book 查询直接报 Unknown column 'share_token_expires_at'。
USE bookkeep;
SET NAMES utf8mb4;   -- 中文注释/数据必须按 UTF-8 解释，否则会双重编码（见 migration_fix_mojibake_comments.sql）

ALTER TABLE book
    ADD COLUMN share_token_expires_at DATETIME NULL
        COMMENT '公示链接到期时间；NULL 或已过期 = 链接失效（分享时重新生成，有效期 3 天）'
        AFTER share_token;

-- 升级前已经发出去的链接补 3 天，避免升级瞬间把班群里的链接全部变成 404；
-- 之后再分享一律重新生成，旧值被覆盖。
UPDATE book
SET share_token_expires_at = DATE_ADD(NOW(), INTERVAL 3 DAY)
WHERE share_token IS NOT NULL
  AND share_token_expires_at IS NULL;