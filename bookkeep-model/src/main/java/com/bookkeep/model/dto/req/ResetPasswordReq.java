package com.bookkeep.model.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用邮件里的一次性 token 重置密码（POST /api/auth/reset-password）。
 */
public record ResetPasswordReq(

        @NotBlank(message = "重置链接无效")
        String token,

        @NotBlank(message = "新密码不能为空")
        @Size(min = 6, max = 32, message = "密码长度需 6–32 位")
        String newPassword
) {
}