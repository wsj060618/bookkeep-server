-- ============================================================
-- bookkeep-server 数据库初始化脚本
-- 适用：MySQL 8.x（本机为 MySQL Server 8.4）
-- 说明：重复执行会因「表已存在」报错（CREATE TABLE 未加 IF NOT EXISTS）
--
-- 排序规则分两层，是有意为之：
--   库 / 表 / 其余列 → utf8mb4_0900_ai_ci（MySQL 8 默认，不区分大小写和重音，
--                     中文与 emoji 排序符合 UCA 9.0；不要用 5.x 时代的 utf8mb4_general_ci）
--   user.username / user.username_active → utf8mb4_0900_as_cs（区分大小写，见 user 表注释）
--   两层混用时注意：不同排序规则的「两列直接比较」会报 Illegal mix of collations，
--   而列与字符串参数比较没问题（参数会被列排序规则强制转换）。
-- ============================================================

-- 1) 建库：utf8mb4，中文/emoji 不乱码。已存在则跳过
CREATE DATABASE IF NOT EXISTS bookkeep DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- 2) 切到 bookkeep 库，后续建表都在这里
USE bookkeep;

-- ------------------------------------------------------------
-- 3) user 用户表
--    current_book_id：当前打开的账本 id，逻辑关联 book.id（无外键）
--    deleted：逻辑删除，0=正常 1=已删（MyBatis-Plus 全局逻辑删除会用到）
--    username：utf8mb4_0900_as_cs → Tom / tom 是两个账号（大小写不再由库默认排序规则决定）
--    username_active：deleted=0 时等于 username，已删时为 NULL（NULL 不参与唯一约束），
--                     于是「注销后名字可重新注册」，而活着的账号之间仍然互斥
-- ------------------------------------------------------------
CREATE TABLE `user` (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  username        VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs
                    NOT NULL COMMENT '登录名，区分大小写',
  password        VARCHAR(100) NOT NULL COMMENT 'BCrypt 密文，禁止存明文',
  nickname        VARCHAR(50)  COMMENT '昵称',
  email           VARCHAR(100) COMMENT '邮箱',
  avatar          VARCHAR(500) COMMENT '头像 URL，可空',
  current_book_id BIGINT       COMMENT '当前账本 id，可空（删除账本时会重指）',
  created_at      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted         TINYINT DEFAULT 0 COMMENT '逻辑删除：0正常 1已删',
  username_active VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs
                    GENERATED ALWAYS AS (IF(`deleted` = 0, `username`, NULL)) STORED
                    COMMENT '仅未删除行等于 username，用于唯一约束',
  UNIQUE KEY uk_user_username_active (username_active),
  KEY idx_user_username (username)
) COMMENT='用户表';

-- ------------------------------------------------------------
-- 4) book 账本表：一本账本 = 一套独立数据（分类/流水/预算）
--    type：当前项目固定 class（班级账本），列保留以便将来扩展其它类型
-- ------------------------------------------------------------
CREATE TABLE book (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  user_id     BIGINT NOT NULL COMMENT '归属用户 user.id',
  name        VARCHAR(50) NOT NULL COMMENT '账本名',
  type        VARCHAR(10) NOT NULL DEFAULT 'class' COMMENT '账本类型，当前固定 class 班级',
  share_token VARCHAR(32) COMMENT '公示页随机 token（免登录只读，见 PLAN §5.9）',
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted     TINYINT DEFAULT 0 COMMENT '逻辑删除：0正常 1已删',
  UNIQUE KEY uk_book_share_token (share_token)
) COMMENT='账本表';

-- ------------------------------------------------------------
-- 5) category 分类表：按账本隔离；注册/新建账本时按 type 预置模板
--    type：income 收入 / expense 支出
-- ------------------------------------------------------------
CREATE TABLE category (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  book_id     BIGINT NOT NULL COMMENT '归属账本 book.id',
  name        VARCHAR(50) NOT NULL COMMENT '分类名，如 餐饮/工资',
  type        VARCHAR(10) NOT NULL COMMENT '收支类型 income收入 expense支出',
  icon        VARCHAR(100) COMMENT '图标语义 key（前端映射图标组件）',
  color       VARCHAR(20)  COMMENT '颜色语义 key（前端映射主题色）',
  sort_order  INT DEFAULT 0 COMMENT '排序，越小越靠前',
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted     TINYINT DEFAULT 0 COMMENT '逻辑删除：0正常 1已删'
) COMMENT='分类表（按账本隔离）';

-- ------------------------------------------------------------
-- 6) transaction 流水表（表名是 SQL 关键字，故用反引号）
--    amount：金额必须 DECIMAL，禁止 double/float
--    trans_date：业务日期（只到天），统计按 DATE_FORMAT(trans_date,'%Y-%m') 比月
-- ------------------------------------------------------------
CREATE TABLE `transaction` (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  book_id     BIGINT NOT NULL COMMENT '归属账本 book.id',
  category_id BIGINT NOT NULL COMMENT '归属分类 category.id',
  type        VARCHAR(10) NOT NULL COMMENT '收支类型 income收入 expense支出（冗余，便于统计）',
  amount      DECIMAL(12,2) NOT NULL COMMENT '金额，两位小数',
  note        VARCHAR(255) COMMENT '备注',
  trans_date  DATE NOT NULL COMMENT '交易日期 yyyy-MM-dd',
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted     TINYINT DEFAULT 0 COMMENT '逻辑删除：0正常 1已删'
) COMMENT='流水表（按账本隔离）';

-- ------------------------------------------------------------
-- 7) budget 预算表：某账本、某分类、某月一条
--    month：'2026-08' 格式（字符串，非 date）
--    uk_book_cat_month：同账本+同分类+同月唯一，防重复
--    注意：本表【没有】deleted 列，预算用物理删除（deleteById 真删）。
--          若加逻辑删除，会和 uk_book_cat_month 唯一键冲突（删了还想重建）。
-- ------------------------------------------------------------
CREATE TABLE budget (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  book_id     BIGINT NOT NULL COMMENT '归属账本 book.id',
  category_id BIGINT NOT NULL COMMENT '归属分类 category.id',
  month       VARCHAR(7) NOT NULL COMMENT '预算月份 YYYY-MM',
  amount      DECIMAL(12,2) NOT NULL COMMENT '预算金额，两位小数',
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  UNIQUE KEY uk_book_cat_month (book_id, category_id, month)
) COMMENT='预算表（物理删除，无 deleted 列）';
