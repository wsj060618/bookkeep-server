-- 流水票据（PLAN §10.9）
--   * 没有 deleted 列：票据随流水的生命周期走（流水软删时票据保留，与回收站语义一致；
--     账本/账号被物理删除时才连记录带文件一起清）
--   * stored_path 是相对 receipt.dir 的路径，唯一事实来源；目录名只是给人看的
CREATE TABLE IF NOT EXISTS transaction_receipt (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键',
  transaction_id BIGINT NOT NULL COMMENT '归属流水 transaction.id',
  title          VARCHAR(30) NOT NULL COMMENT '票据标题（用户可改，≤30 字）',
  original_name  VARCHAR(255) COMMENT '上传时的原始文件名，仅用于展示',
  stored_path    VARCHAR(255) NOT NULL COMMENT '相对 receipt.dir 的路径，如 计科2301班费_12/2026-11/xx.jpg',
  content_type   VARCHAR(50) NOT NULL COMMENT '按魔数识别出来的类型：image/jpeg|image/png|image/webp',
  size_bytes     BIGINT NOT NULL COMMENT '文件大小（字节）',
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  KEY idx_receipt_transaction (transaction_id)
) COMMENT='流水票据（图片文件落盘，库里只存相对路径）';