package com.bookkeep.model.dto.req;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量操作（删除 / 彻底删除 / 生成分享链接）共用的请求体：一串 id。
 * <p>上限 200 是为了挡住「一次删几千行」的脚本；正常使用根本到不了。
 */
public record BatchIdsReq(

        @NotEmpty(message = "请先选择至少一项")
        @Size(max = 200, message = "一次最多操作 200 项")
        List<Long> ids
) {
}