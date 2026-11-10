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
SET NAMES utf8mb4;   -- 中文注释/数据必须按 UTF-8 解释，否则会双重编码（见 migration_fix_mojibake_comments.sql）

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
                    NOT NULL COMMENT '显示名，区分大小写；可在账号设置里改',
  password        VARCHAR(100) NOT NULL COMMENT 'BCrypt 密文，禁止存明文',
  token_version   INT NOT NULL DEFAULT 0 COMMENT '令牌版本：改密码/重置时 +1，旧 JWT 立即失效',
  email           VARCHAR(100) NOT NULL COMMENT '邮箱，登录凭据：必填且未删除行内唯一',
  avatar          VARCHAR(500) COMMENT '头像 URL，可空',
  current_book_id BIGINT       COMMENT '当前账本 id，可空（删除账本时会重指）',
  created_at      DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted         TINYINT DEFAULT 0 COMMENT '逻辑删除：0正常 1已删',
  username_active VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_as_cs
                    GENERATED ALWAYS AS (IF(`deleted` = 0, `username`, NULL)) STORED
                    COMMENT '仅未删除行等于 username，用于唯一约束',
  email_active    VARCHAR(100)
                    GENERATED ALWAYS AS (IF(`deleted` = 0, `email`, NULL)) STORED
                    COMMENT '仅未删除行等于 email，用于唯一约束',
  UNIQUE KEY uk_user_username_active (username_active),
  UNIQUE KEY uk_user_email_active (email_active),
  KEY idx_user_username (username)
) COMMENT='用户表';

-- ------------------------------------------------------------
-- 3.1) password_reset_token 找回密码一次性 token
--    token_hash = sha256(明文 token)：库被拖走也拿不到可用链接
--    used_at 非空即已作废；重新申请会把该用户其它未用 token 一起作废
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS password_reset_token (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  user_id    BIGINT      NOT NULL COMMENT 'user.id',
  token_hash CHAR(64)    NOT NULL COMMENT 'sha256(明文 token)，库里不存明文',
  expires_at DATETIME    NOT NULL COMMENT '过期时间：签发后 15 分钟',
  used_at    DATETIME    NULL COMMENT '使用时间；非空即已作废',
  created_at DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '签发时间',
  UNIQUE KEY uk_prt_token_hash (token_hash),
  KEY idx_prt_user (user_id)
) COMMENT='找回密码一次性 token';

-- ------------------------------------------------------------
-- 4) book 账本表：一本账本 = 一套独立数据（分类/流水/票据）
--    type：当前项目固定 class（班级账本），列保留以便将来扩展其它类型
-- ------------------------------------------------------------
CREATE TABLE book (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  user_id     BIGINT NOT NULL COMMENT '归属用户 user.id',
  name        VARCHAR(50) NOT NULL COMMENT '账本名',
  type        VARCHAR(10) NOT NULL DEFAULT 'class' COMMENT '账本类型，当前固定 class 班级',
  category_template_id BIGINT NULL COMMENT '当前分类来自哪套模板；NULL = 老数据（历史版本里默认模板还没有行）',
  semester_end DATE NULL COMMENT '学期截止日期（可空）；只用于展示与提示，不影响任何统计口径',
  archived    TINYINT NOT NULL DEFAULT 0 COMMENT '学期归档：1 = 只读（仍可改名/改截止日期/取消归档/导出/公示）',
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted     TINYINT DEFAULT 0 COMMENT '逻辑删除：0正常 1已删',
  deleted_at  DATETIME NULL COMMENT '进入回收站的时间（deleted=1 时才有值）；满 15 天连同分类/流水彻底删除'
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
  template_item_id BIGINT NULL COMMENT '来自哪个模板条目 category_template_item.id；NULL = 用户自建（记一笔里新建）',
  icon        VARCHAR(100) COMMENT '图标语义 key（前端映射图标组件）',
  color       VARCHAR(20)  COMMENT '颜色语义 key（前端映射主题色）',
  sort_order  INT DEFAULT 0 COMMENT '排序，越小越靠前',
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted     TINYINT DEFAULT 0 COMMENT '逻辑删除：0正常 1已删',
  KEY idx_category_template_item (template_item_id)
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

-- 预算（budget）已移出范围并删表（PLAN §2.4）：不建表，恢复时按 §2.4 的设计重建。

-- ------------------------------------------------------------
-- 7) category_template 分类模板：**跟账号走**，一套模板可用于该用户的任意账本
--    注册时会落一套真实的「班级班费」（CategoryPresets.classCategories() 的 11 条），
--    所以它和自建模板一样可改名/增删条目/删除；id=0 只在账号一套模板都没有时作为只读投影兜底。
--    本表物理删除（无 deleted 列）：模板条目是整体替换的，逻辑删除只会越攒越多。
-- ------------------------------------------------------------
CREATE TABLE category_template (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  user_id     BIGINT NOT NULL COMMENT '归属用户 user.id',
  name        VARCHAR(50) NOT NULL COMMENT '模板名',
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  KEY idx_ct_user (user_id)
) COMMENT='自定义分类模板（跟账号走，物理删除）';

-- ------------------------------------------------------------
-- 8) category_template_item 模板条目：字段与 category 一一对应，应用模板时按此逐条复制
--    没有 deleted 列：改模板 = 整体删掉重插（PLAN §10.4）
--    **一行 = 某套模板里的一个条目**：`template_id` 就是关联，条目属于模板、不跨模板共用。
--    所以不同模板之间出现同名同图标的行是正常的（各自的副本，可独立改）；
--    真正该为 0 的是「template_id 指向已不存在的模板」的孤儿行。
-- ------------------------------------------------------------
CREATE TABLE category_template_item (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  template_id BIGINT NOT NULL COMMENT '归属模板 category_template.id',
  name        VARCHAR(50) NOT NULL COMMENT '分类名',
  type        VARCHAR(10) NOT NULL COMMENT '收支类型 income收入 expense支出',
  icon        VARCHAR(100) NULL COMMENT '图标语义 key',
  color       VARCHAR(20)  NULL COMMENT '颜色语义 key',
  sort_order  INT DEFAULT 0 COMMENT '排序，越小越靠前',
  KEY idx_cti_template (template_id)
) COMMENT='模板条目（物理删除）';

-- ------------------------------------------------------------
-- 9) share_link 公示链接：**一条链接可以带 1..N 本账本**（PLAN §5.9）
--     每个账号最多一条有效链接：重新分享会删掉旧链接，泄漏时再分享一次即可作废
-- ------------------------------------------------------------
CREATE TABLE share_link (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  user_id     BIGINT NOT NULL COMMENT '归属用户 user.id',
  token       VARCHAR(32) NOT NULL COMMENT '32 位随机 token（免登录只读）',
  expires_at  DATETIME NOT NULL COMMENT '到期时间（创建时 now + 3 天）',
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  UNIQUE KEY uk_share_link_token (token),
  KEY idx_share_link_user (user_id)
) COMMENT='公示链接（一条链接 = 1..N 本账本）';

-- ------------------------------------------------------------
-- 10) share_link_book 公示链接包含的账本（物理删除；账本彻底删除时一并清掉）
-- ------------------------------------------------------------
CREATE TABLE share_link_book (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  share_link_id BIGINT NOT NULL COMMENT '归属链接 share_link.id',
  book_id       BIGINT NOT NULL COMMENT '被包含的账本 book.id',
  sort_order    INT DEFAULT 0 COMMENT '展示顺序',
  KEY idx_slb_link (share_link_id),
  KEY idx_slb_book (book_id)
) COMMENT='公示链接包含的账本（物理删除）';

-- ------------------------------------------------------------
-- 11) transaction_receipt 流水票据（PLAN §10.9）
--   没有 deleted 列：票据随流水生命周期（流水软删时保留，账本/账号物理删除时连文件一起清）
--   stored_path 是相对 receipt.dir 的路径：{账本名}_{bookId}/{yyyy-MM}/{uuid}.{ext}
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS transaction_receipt (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  transaction_id BIGINT NOT NULL COMMENT '归属流水 transaction.id',
  title          VARCHAR(30) NOT NULL COMMENT '票据标题（用户可改，≤30 字）',
  original_name  VARCHAR(255) COMMENT '上传时的原始文件名，仅用于展示',
  stored_path    VARCHAR(255) NOT NULL COMMENT '相对 receipt.dir 的路径',
  content_type   VARCHAR(50) NOT NULL COMMENT '按魔数识别：image/jpeg|image/png|image/webp',
  size_bytes     BIGINT NOT NULL COMMENT '文件大小（字节）',
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  KEY idx_receipt_transaction (transaction_id)
) COMMENT='流水票据（图片落盘，库里只存相对路径）';