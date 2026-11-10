-- ------------------------------------------------------------
-- 一次性迁移：账本类型收敛为单一「班级账本」+ 增加公示页 token 列
--
-- 背景：项目需求收敛为「生活委员记班费」，personal / merchant 两套模板已删除，
--       代码固定写 class。已在用的库需要把历史行也改成 class，
--       否则按 type 查询会和代码里的常量对不上。
--       同时为 §5.9 的免登录公示页增加 book.share_token。
--
-- 执行方式（PowerShell 不支持 < 重定向，用 source）：
--   & "D:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe" -uroot -p -e "source D:/coding/bookkeep-server/sql/migration_book_type_class.sql"
--
-- 注意：
--   1. 幂等，可重复执行（UPDATE 只命中的不是 class 的行）
--   2. type 列加 DEFAULT 'class'，新插入不传该列也能落 class
--   3. 历史账本的分类仍是旧的 personal / merchant 模板，不会自动替换；
--      需要班级模板的话，重新注册一个账号即可（或手工删掉旧分类后重新建）
-- ------------------------------------------------------------
USE bookkeep;
SET NAMES utf8mb4;   -- 中文注释/数据必须按 UTF-8 解释，否则会双重编码（见 migration_fix_mojibake_comments.sql）

ALTER TABLE book
  MODIFY type VARCHAR(10) NOT NULL DEFAULT 'class' COMMENT '账本类型，当前固定 class 班级';

UPDATE book SET type = 'class' WHERE type <> 'class';

-- 公示页 token：历史行先留空（NULL 不参与唯一索引），注册流程会给新账本生成
ALTER TABLE book
  ADD COLUMN share_token VARCHAR(32) COMMENT '公示页随机 token（免登录只读）' AFTER type,
  ADD UNIQUE KEY uk_book_share_token (share_token);