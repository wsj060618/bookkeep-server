package com.bookkeep.model.dto.resp;

import java.util.List;

/**
 * 流水导入结果（POST /api/transactions/import）。
 * <p>**行级失败不阻塞其它行**：能解析的行照常入库，失败的行逐条给原因，
 * 前端把它们列在表格里让用户改文件重传。
 *
 * @param success 成功写入的条数
 * @param failed  失败明细（按文件行号升序）
 */
public record ImportResultResp(
        int success,
        List<FailedRow> failed
) {

    /**
     * @param line   文件里的行号（**1 基，含表头那行**，和 Excel 左侧的行号一致，方便用户定位）
     * @param reason 失败原因（如「分类「聚餐」不存在」「金额必须大于 0」）
     */
    public record FailedRow(int line, String reason) {
    }
}