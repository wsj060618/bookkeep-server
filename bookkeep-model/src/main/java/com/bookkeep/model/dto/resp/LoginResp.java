package com.bookkeep.model.dto.resp;

import java.util.List;

/** 登录响应；books 和 user 平级 */
public record LoginResp(
        String token,
        UserResp user,
        List<BookResp> books
) {
}
