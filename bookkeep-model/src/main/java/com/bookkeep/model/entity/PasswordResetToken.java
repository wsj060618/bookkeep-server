package com.bookkeep.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 找回密码的一次性 token。
 * <p>库里只存 {@code sha256(明文)}：即使库被拖走，也拼不出可用的重置链接。
 * <p>这个表**没有 deleted 列**：作废靠 {@code used_at}，不是逻辑删除。
 */
@Data
@TableName("password_reset_token")
public class PasswordResetToken {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** sha256 的 64 位十六进制 */
    private String tokenHash;

    private LocalDateTime expiresAt;

    /** 非空 = 已使用/已作废 */
    private LocalDateTime usedAt;

    private LocalDateTime createdAt;
}