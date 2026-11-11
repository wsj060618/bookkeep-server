package com.bookkeep.model.dto.resp;

import java.util.List;

/**
 * 流水导入结果：成功的条数 + 失败明细（行号 + 原因）。
 * <p>{@code missingCategories} 非空表示「文件里用到、账本里还没有」的分类：
 * 这时**一行都没有写库**，前端弹窗让用户决定要不要自动新建（前端带上 createMissing 再导一次）。
 */
public record ImportResultResp(
        int success,
        List<FailedRow> failed,
        List<MissingCategory> missingCategories
) {

    public ImportResultResp(int success, List<FailedRow> failed) {
        this(success, failed, List.of());
    }

    /** line 是文件行号（1 起，含表头） */
    public record FailedRow(int line, String reason) {
    }

    /** 文件里用到、当前账本里不存在的分类；type 是 income / expense */
    public record MissingCategory(String name, String type) {
    }
}
