package com.bookkeep.model.dto.resp;

import com.bookkeep.model.entity.TransactionReceipt;

/**
 * 公示页的票据：**只给 id 与标题**，不含原始文件名、磁盘路径、流水 id、上传者。
 * <p>匿名访客能拿到的就这些，图片内容走独立接口（见 PublicController）。
 */
public record PublicReceiptResp(Long id, String title) {

    public static PublicReceiptResp from(TransactionReceipt receipt) {
        return new PublicReceiptResp(receipt.getId(), receipt.getTitle());
    }
}