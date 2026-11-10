package com.bookkeep.model.dto.resp;

import java.util.List;

/**
 * 票据导入结果（单笔 zip / 账本包里的图片部分）。
 * <p>逐张处理、失败不阻塞其它张：前端把失败文件列出来让用户改名或转格式后重传。
 */
public record ReceiptImportResp(int success, List<FailedFile> failed) {

    /** @param file 压缩包内的相对路径 */
    public record FailedFile(String file, String reason) {
    }
}