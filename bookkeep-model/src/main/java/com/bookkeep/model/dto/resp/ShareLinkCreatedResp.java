package com.bookkeep.model.dto.resp;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分享结果（`POST /api/books/share`）：**一条链接** + 它包含的账本。
 * <p>URL 由前端用 `origin + /report/ + token` 拼（localhost / 局域网 IP / 域名都不同，不能在后端拼死）。
 *
 * @param token     32 位公示 token
 * @param expiresAt 到期时间（now + 3 天）
 * @param books     这条链接能看到的账本（按勾选顺序）
 *
 * <p>**每次分享都换新 token**：旧链接立即失效，泄漏时再点一次「分享」就能掐掉它。
 */
public record ShareLinkCreatedResp(
        String token,
        LocalDateTime expiresAt,
        List<SharedBook> books
) {

    public record SharedBook(Long id, String name) {
    }
}