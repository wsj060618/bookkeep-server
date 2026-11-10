package com.bookkeep.model.dto.resp;

import java.time.LocalDateTime;
import java.util.List;

/** 分享结果：一条链接 + 它包含的账本；URL 由前端用 origin 拼 */
public record ShareLinkCreatedResp(
        String token,
        LocalDateTime expiresAt,
        List<SharedBook> books
) {

    public record SharedBook(Long id, String name) {
    }
}