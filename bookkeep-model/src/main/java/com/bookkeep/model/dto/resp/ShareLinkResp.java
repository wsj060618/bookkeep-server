package com.bookkeep.model.dto.resp;

import java.time.LocalDateTime;

/**
 * 分享结果（POST /api/books/{id}/share）。
 * <p>只返回 token 与到期时间，<strong>不返回完整 URL</strong>：origin 由前端决定（localhost / 局域网 IP / 未来域名），
 * 后端拼出来的一定有某一端是错的。
 */
public record ShareLinkResp(
        String token,
        LocalDateTime expiresAt
) {
}