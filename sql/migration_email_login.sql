-- 改成「邮箱登录 + 用户名可改 + 去掉昵称」（只跑一次；新库用 schema.sql）。
-- ⚠️ 执行前先 mysqldump 备份（deploy/backup/before-email-login-*.sql）。
USE bookkeep;

-- 1) 邮箱成为登录凭据：必须唯一（只约束未删除行，注销后释放，和 username 的套路一致）
--    加之前确认没有重复：SELECT email, COUNT(*) FROM user WHERE deleted=0 GROUP BY email HAVING COUNT(*) > 1;
ALTER TABLE `user`
    ADD COLUMN email_active VARCHAR(100)
        GENERATED ALWAYS AS (IF(`deleted` = 0, `email`, NULL)) STORED
        COMMENT '仅未删除行等于 email，用于唯一约束',
    ADD UNIQUE KEY uk_user_email_active (email_active);

-- 2) 昵称取消：显示名统一用 username（username 允许在账号设置里改）
ALTER TABLE `user` DROP COLUMN nickname;