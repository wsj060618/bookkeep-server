package com.bookkeep.model.dto.resp;

import java.time.LocalDateTime;

/**
 * 批量分享时，一本账本对应一条公示链接。
 * <p>前端用 {@code window.location.origin + "/report/" + token} 拼地址，所以这里只给 token。
 * <p>已有**未过期**链接的账本会原样复用（不重新生成），避免「只是想把链接都列出来」却把
 * 已经发到班群的旧链接顶掉；只有还没链接 / 已过期才新建一条。
 *
 * @param bookId    账本 id
 * @param bookName  账本名（下拉里展示用）
 * @param token     32 位公示 token
 * @param expiresAt 到期时间（now + 3 天，或复用的旧链接原到期时间）
 * @param reused    true = 复用了已有链接，false = 本次新生成
 */
public record BookShareResp(
        Long bookId,
        String bookName,
        String token,
        LocalDateTime expiresAt,
        boolean reused
) {
}