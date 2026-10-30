package com.bookkeep.model.dto.resp;

import java.util.List;

/**
 * 登录响应体（POST /api/auth/login）
 * <p>结构 {@code {token, user, books}}：{@code user} 复用 {@link UserResp}（与注册/ {@code /me} 同一形状），
 * {@code books} 平铺在外层而不是塞进 {@code user}——注册接口就不必多返一个空数组，
 * 后续 {@code /api/auth/me} 也能直接套这个形状（只是不带 token）。
 *
 * @param token JWT，前端存本地，之后所有请求带 {@code Authorization: Bearer <token>}
 * @param user  用户信息（不含密码）
 * @param books 该用户的全部账本，{@code current} 标出当前账本
 */
public record LoginResp(
        String token,
        UserResp user,
        List<BookResp> books
) {
}
