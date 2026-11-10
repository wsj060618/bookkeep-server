-- ============================================================
-- 迁移：修复中文注释「双重编码」乱码
--
-- 现象：information_schema 里部分表/列的 TABLE_COMMENT / COLUMN_COMMENT 显示成
--       「鑷畾涔夊垎绫绘ā鏉匡紙璺熻处鍙疯蛋锛岀墿鐞嗗垹闄わ級」这种乱码。
--
-- 原因：建这些表 / 加这些列时是用 mysql.exe 执行 UTF-8 的迁移脚本，但 Windows 控制台是 cp936，
--       mysql 客户端把 character_set_client 当成 gbk，于是把 UTF-8 字节按 GBK 解释后
--       再以 utf8mb4 存进去 = 双重编码。只影响**注释**，业务数据是 JDBC 写入的，没受影响。
--
-- 修法：重新声明一遍注释。执行时**必须**带 UTF-8 客户端字符集：
--       mysql -uroot -p --default-character-set=utf8mb4 -e "source .../migration_fix_mojibake_comments.sql"
--       （本文件开头也 SET NAMES 了一下，双保险）
--
-- 一次性操作：可重复执行（幂等，只是把注释覆盖成正确值）。
-- ============================================================

USE bookkeep;
SET NAMES utf8mb4;

-- ---------- category_template ----------
ALTER TABLE `category_template`
  MODIFY COLUMN `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  MODIFY COLUMN `user_id`    BIGINT       NOT NULL COMMENT '归属用户 user.id',
  MODIFY COLUMN `name`       VARCHAR(50)  NOT NULL COMMENT '模板名',
  MODIFY COLUMN `created_at` DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `updated_at` DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  COMMENT = '自定义分类模板（跟账号走，物理删除）';

-- ---------- category_template_item ----------
ALTER TABLE `category_template_item`
  MODIFY COLUMN `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  MODIFY COLUMN `template_id` BIGINT       NOT NULL COMMENT '归属模板 category_template.id',
  MODIFY COLUMN `name`        VARCHAR(50)  NOT NULL COMMENT '分类名',
  MODIFY COLUMN `type`        VARCHAR(10)  NOT NULL COMMENT '收支类型 income收入 expense支出',
  MODIFY COLUMN `icon`        VARCHAR(100) DEFAULT NULL COMMENT '图标语义 key',
  MODIFY COLUMN `color`       VARCHAR(20)  DEFAULT NULL COMMENT '颜色语义 key',
  MODIFY COLUMN `sort_order`  INT          DEFAULT 0 COMMENT '排序，越小越靠前',
  COMMENT = '模板条目（物理删除）';

-- ---------- password_reset_token ----------
ALTER TABLE `password_reset_token`
  MODIFY COLUMN `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
  MODIFY COLUMN `user_id`    BIGINT      NOT NULL COMMENT 'user.id',
  MODIFY COLUMN `token_hash` CHAR(64)    NOT NULL COMMENT 'sha256(明文 token)，库里不存明文',
  MODIFY COLUMN `expires_at` DATETIME    NOT NULL COMMENT '过期时间：签发后 15 分钟',
  MODIFY COLUMN `used_at`    DATETIME    DEFAULT NULL COMMENT '使用时间；非空即已作废',
  MODIFY COLUMN `created_at` DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '签发时间',
  COMMENT = '找回密码一次性 token';

-- ---------- transaction_receipt ----------
ALTER TABLE `transaction_receipt`
  MODIFY COLUMN `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  MODIFY COLUMN `transaction_id` BIGINT       NOT NULL COMMENT '归属流水 transaction.id',
  MODIFY COLUMN `title`          VARCHAR(30)  NOT NULL COMMENT '票据标题（用户可改，≤30 字）',
  MODIFY COLUMN `original_name`  VARCHAR(255) DEFAULT NULL COMMENT '上传时的原始文件名，仅用于展示',
  MODIFY COLUMN `stored_path`    VARCHAR(255) NOT NULL COMMENT '相对 receipt.dir 的路径',
  MODIFY COLUMN `content_type`   VARCHAR(50)  NOT NULL COMMENT '按魔数识别：image/jpeg|image/png|image/webp',
  MODIFY COLUMN `size_bytes`     BIGINT       NOT NULL COMMENT '文件大小（字节）',
  MODIFY COLUMN `created_at`     DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  COMMENT = '流水票据（图片落盘，库里只存相对路径）';

-- ---------- user（token_version / email 是后续迁移加的列，同样被写坏） ----------
ALTER TABLE `user`
  MODIFY COLUMN `username`      VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs
                                NOT NULL COMMENT '显示名，区分大小写；可在账号设置里改',
  MODIFY COLUMN `token_version` INT NOT NULL DEFAULT 0 COMMENT '令牌版本：改密码/重置时 +1，旧 JWT 立即失效',
  MODIFY COLUMN `email`         VARCHAR(100) NOT NULL COMMENT '邮箱，登录凭据：必填且未删除行内唯一',
  MODIFY COLUMN `username_active` VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs
                                GENERATED ALWAYS AS (IF(`deleted` = 0, `username`, NULL)) STORED
                                COMMENT '仅未删除行等于 username，用于唯一约束',
  MODIFY COLUMN `email_active`  VARCHAR(100)
                                GENERATED ALWAYS AS (IF(`deleted` = 0, `email`, NULL)) STORED
                                COMMENT '仅未删除行等于 email，用于唯一约束';

-- ---------- book.category_template_id（migration_category_template.sql 加的列） ----------
ALTER TABLE `book`
  MODIFY COLUMN `category_template_id` BIGINT NULL
      COMMENT '当前分类来自哪套模板；NULL = 老数据（历史版本里默认模板还没有行）';

-- 复查：4 张表的表注释应恢复成中文
SELECT TABLE_NAME, TABLE_COMMENT
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'bookkeep'
  AND TABLE_NAME IN ('category_template', 'category_template_item',
                     'password_reset_token', 'transaction_receipt')
ORDER BY TABLE_NAME;
