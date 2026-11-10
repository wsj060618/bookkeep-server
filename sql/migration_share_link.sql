-- ============================================================
-- 迁移：公示链接改成「一条链接带多本账本」（PLAN §5.9 口径调整）
--
-- 背景：原来每本账本一个 share_token，分享几本就要发几条链接。
--       现在「一条链接访问所有选中的账本」：新增 share_link + share_link_book，
--       token 从 book 挪到 share_link 上。
--
-- 数据保全：把 book 上**仍在有效期**的 token 迁成 share_link（每本一条），
--           已经发到班群的旧链接继续能打开；过期的不迁（本来也打不开）。
--
-- 幂等性：重复执行会在 CREATE TABLE 报「already exists」/ 在 DROP COLUMN 报「check that column/key exists」，
--         看到这些错说明已经迁移过了，忽略即可。
-- ============================================================

USE bookkeep;
SET NAMES utf8mb4;   -- 中文注释/数据必须按 UTF-8 解释，否则会双重编码（见 migration_fix_mojibake_comments.sql）

CREATE TABLE IF NOT EXISTS share_link (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  user_id     BIGINT NOT NULL COMMENT '归属用户 user.id',
  token       VARCHAR(32) NOT NULL COMMENT '32 位随机 token（免登录只读）',
  expires_at  DATETIME NOT NULL COMMENT '到期时间（创建时 now + 3 天）',
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  UNIQUE KEY uk_share_link_token (token),
  KEY idx_share_link_user (user_id)
) COMMENT='公示链接（一条链接 = 1..N 本账本）';

CREATE TABLE IF NOT EXISTS share_link_book (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  share_link_id BIGINT NOT NULL COMMENT '归属链接 share_link.id',
  book_id       BIGINT NOT NULL COMMENT '被包含的账本 book.id',
  sort_order    INT DEFAULT 0 COMMENT '展示顺序',
  KEY idx_slb_link (share_link_id),
  KEY idx_slb_book (book_id)
) COMMENT='公示链接包含的账本（物理删除）';

-- 1) 把每本账本上仍在有效期内的 token 迁成一条单本链接
INSERT INTO share_link (user_id, token, expires_at)
SELECT b.user_id, b.share_token, b.share_token_expires_at
FROM book b
WHERE b.share_token IS NOT NULL
  AND b.share_token_expires_at IS NOT NULL
  AND b.share_token_expires_at > NOW()
  AND NOT EXISTS (SELECT 1 FROM share_link s WHERE s.token = b.share_token);

INSERT INTO share_link_book (share_link_id, book_id, sort_order)
SELECT s.id, b.id, 1
FROM book b
JOIN share_link s ON s.token = b.share_token
WHERE NOT EXISTS (
    SELECT 1 FROM share_link_book sb WHERE sb.share_link_id = s.id AND sb.book_id = b.id
);

-- 2) 旧的每本一 token 的列不再使用（数据已迁到 share_link）
ALTER TABLE book DROP INDEX uk_book_share_token;
ALTER TABLE book DROP COLUMN share_token;
ALTER TABLE book DROP COLUMN share_token_expires_at;

-- 复查：当前有效链接数量
SELECT COUNT(*) AS active_links FROM share_link WHERE expires_at > NOW();