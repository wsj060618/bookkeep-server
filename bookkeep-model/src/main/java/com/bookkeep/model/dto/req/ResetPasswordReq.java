package com.bookkeep.model.dto.req;

import com.bookkeep.model.validation.PasswordRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 用邮件里的一次性 token 重置密码（POST /api/auth/reset-password）。
 */
public record ResetPasswordReq(

        @NotBlank(message = "重置链接无效")
        String token,

        @NotBlank(message = "新密码不能为空")
        @Pattern(regexp = PasswordRules.PATTERN, message = PasswordRules.MESSAGE)
        String newPassword
) {
}
