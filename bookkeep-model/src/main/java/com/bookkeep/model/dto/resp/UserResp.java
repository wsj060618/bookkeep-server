package com.bookkeep.model.dto.resp;

import com.bookkeep.model.entity.User;

/**
 * 用户信息响应体（注册、登录、/api/auth/me 共用）。
 * <p>故意只暴露对前端有用的字段：**绝不包含 password**、deleted、createdAt/updatedAt
 * 这类内部字段，避免实体直接出网（MyBatis-Plus 实体早晚会加字段，直接返回实体等于把库结构泄露给前端）。
 * <p>没有 nickname：显示名统一用 username（可在账号设置里改）。
 */
public record UserResp(
        Long id,
        String username,
        String email,
        String avatar,
        /* 当前账本 id，前端切账本前靠它决定默认展示哪本 */
        Long currentBookId
) {

    /** 实体 → 响应体（只做字段搬运，不碰数据库） */
    public static UserResp from(User user) {
        return new UserResp(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getAvatar(),
                user.getCurrentBookId()
        );
    }
}