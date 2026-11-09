-- ============================================================
-- 迁移：多套分类模板（PLAN §10.4 口径调整）
-- 适用：已经建好库的环境（新库直接跑 schema.sql 即可，不用执行本文件）
-- 幂等性：MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，重复执行会报「Duplicate column」，
--         看到这个错说明已经迁移过了，忽略即可。
-- ============================================================

USE bookkeep;

-- 1) 账本记录「当前分类来自哪套模板」：NULL = 内置班级模板（id=0 不入库）
ALTER TABLE book
  ADD COLUMN category_template_id BIGINT NULL
  COMMENT '当前分类来自哪套自定义模板；NULL = 内置「班级班费」模板';

-- 2) 自定义模板（跟账号走，一套模板可用于该用户的任意账本；物理删除）
CREATE TABLE IF NOT EXISTS category_template (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  user_id     BIGINT NOT NULL COMMENT '归属用户 user.id',
  name        VARCHAR(50) NOT NULL COMMENT '模板名',
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  KEY idx_ct_user (user_id)
) COMMENT='自定义分类模板（跟账号走，物理删除）';

-- 3) 模板条目：字段与 category 一一对应，应用模板时按此逐条复制（物理删除）
CREATE TABLE IF NOT EXISTS category_template_item (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  template_id BIGINT NOT NULL COMMENT '归属模板 category_template.id',
  name        VARCHAR(50) NOT NULL COMMENT '分类名',
  type        VARCHAR(10) NOT NULL COMMENT '收支类型 income收入 expense支出',
  icon        VARCHAR(100) NULL COMMENT '图标语义 key',
  color       VARCHAR(20)  NULL COMMENT '颜色语义 key',
  sort_order  INT DEFAULT 0 COMMENT '排序，越小越靠前',
  KEY idx_cti_template (template_id)
) COMMENT='模板条目（物理删除）';
