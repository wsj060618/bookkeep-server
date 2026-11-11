package com.bookkeep.model.dto.resp;

import java.util.List;

/**
 * 账本包（zip）导入结果：流水与票据分开报，前端分开展示。
 *
 * @param success          成功导入的流水条数
 * @param failed           失败的流水（CSV / manifest 行号 + 原因）
 * @param receipts         票据部分的结果
 * @param missingCategories 非空表示包里有账本还没有的分类，此时**什么都没写库**
 */
public record ZipImportResp(
        int success,
        List<ImportResultResp.FailedRow> failed,
        ReceiptImportResp receipts,
        List<ImportResultResp.MissingCategory> missingCategories
) {

    public ZipImportResp(int success, List<ImportResultResp.FailedRow> failed, ReceiptImportResp receipts) {
        this(success, failed, receipts, List.of());
    }
}
