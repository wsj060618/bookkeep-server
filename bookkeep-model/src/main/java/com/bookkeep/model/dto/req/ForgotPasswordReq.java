package com.bookkeep.model.dto.req;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 申请重置密码（POST /api/auth/forgot-password）。
 * <p>用户名 + 邮箱必须同时匹配：`user.email` 没有唯一索引，只凭邮箱定位不到唯一账号。
 */
public record ForgotPasswordReq(

        @NotBlank(message = "用户名不能为空")
        String username,

        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        @Size(max = 100, message = "邮箱不能超过 100 个字符")
        String email
) {
}