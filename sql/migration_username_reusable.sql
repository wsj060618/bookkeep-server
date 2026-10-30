-- ============================================================
-- 迁移：把「旧库」升到当前 schema.sql 的结构
--   · 库/表默认排序规则 general_ci → utf8mb4_0900_ai_ci
--   · user.username 改为区分大小写（utf8mb4_0900_as_cs）
--   · 注销（逻辑删除）后用户名可被重新注册（生成列 + 唯一索引）
--
-- ⚠️ 只在按旧 schema 建过库的环境上跑。
--    如果你的库是用当前 sql/schema.sql 重建的，说明结构已经对了，不要再执行本文件
--    （第一步 DROP INDEX `username` 会直接报「index 不存在」）。
--    本文件是一次性的，不能重复执行。
--
-- 执行：
--   & "D:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -uroot -p -e "source D:/coding/bookkeep-server/sql/migration_username_reusable.sql"
-- ============================================================

USE bookkeep;

-- ---------- 1) 库级默认排序规则 ----------
-- general_ci 是 MySQL 5.x 时代的老规则：排序不符合 Unicode，emoji/生僻字比较不可靠。
ALTER DATABASE bookkeep CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- ---------- 2) 其余四张表跟着库默认 ----------
-- CONVERT TO 会重建表及其索引；开发库数据量很小，秒级完成。
ALTER TABLE `book`         CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE `category`     CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE `transaction`  CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE `budget`       CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- ---------- 3) user 表：先整体转规则，再把 username 改回区分大小写 ----------
-- 顺序不能反：CONVERT TO 会把 username 也刷成 0900_ai_ci，必须在它之后再 MODIFY 回来。
ALTER TABLE `user` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- 丢掉「整表唯一」的旧索引，换成只约束未删除行的唯一索引。
-- 旧索引名是 MySQL 按列名自动起的 `username`；若报 Can't DROP，先 SHOW INDEX FROM `user` 看实际 Key_name。
ALTER TABLE `user`
  DROP INDEX `username`,
  MODIFY `username` VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs
    NOT NULL COMMENT '登录名，区分大小写';

-- username_active：deleted=0 时等于 username，已删时为 NULL。
-- MySQL 唯一索引允许多个 NULL → 墓碑行不占名字，活账号之间仍然互斥。
ALTER TABLE `user`
  ADD COLUMN `username_active` VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs
    GENERATED ALWAYS AS (IF(`deleted` = 0, `username`, NULL)) STORED
    COMMENT '仅未删除行等于 username，用于唯一约束',
  ADD UNIQUE KEY `uk_user_username_active` (`username_active`),
  ADD KEY `idx_user_username` (`username`);

-- 注意：
-- 1) 改 collation 不会产生重复数据 —— 旧索引本就不区分大小写，存量用户名必定互不相同。
-- 2) 以后「恢复已注销账号」（deleted 改回 0）时，若名字已被别人注册，
--    唯一索引会报 Duplicate entry 拦住，这是期望行为：不允许两个活账号同名。
-- 3) 封禁不要复用 deleted 字段。生成列只看 deleted，用 deleted 表示封禁会让被封者
--    的名字被冒名者抢注；封禁应另加 status 列，并保持 deleted=0。
-- 4) 迁移后用 SHOW CREATE TABLE `user` 复核：username/username_active 应是
--    utf8mb4_0900_as_cs，其余列应是 utf8mb4_0900_ai_ci。
