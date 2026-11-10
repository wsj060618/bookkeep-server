package com.bookkeep.model.dto.resp;

import java.util.List;

/**
 * 账本包（zip）导入结果：流水与票据分开报，前端分两栏展示。
 *
 * @param success  成功导入的流水笔数
 * @param failed   失败的流水（带 CSV/manifest 行号与原因）
 * @param receipts 票据部分的结果
 */
public record ZipImportResp(int success, List<ImportResultResp.FailedRow> failed, ReceiptImportResp receipts) {
}