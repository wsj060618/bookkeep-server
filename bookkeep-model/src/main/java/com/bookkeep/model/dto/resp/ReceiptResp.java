package com.bookkeep.model.dto.resp;

import com.bookkeep.model.entity.TransactionReceipt;

import java.time.LocalDateTime;

/**
 * 票据列表项（不含文件内容，列表只给前端展示所需的元数据）。
 * <p>不给磁盘路径：路径是服务端实现细节，暴露出去等于告诉别人目录结构。
 */
public record ReceiptResp(
        Long id,
        String title,
        String originalName,
        Long sizeBytes,
        LocalDateTime createdAt
) {

    public static ReceiptResp from(TransactionReceipt receipt) {
        return new ReceiptResp(
                receipt.getId(),
                receipt.getTitle(),
                receipt.getOriginalName(),
                receipt.getSizeBytes(),
                receipt.getCreatedAt());
    }
}