package com.bookkeep.model.dto.resp;

import java.util.List;

/** 流水导入结果：行级失败不阻塞其它行 */
public record ImportResultResp(
        int success,
        List<FailedRow> failed
) {

    /** line 是文件里的 1 基行号（含表头） */
    public record FailedRow(int line, String reason) {
    }
}