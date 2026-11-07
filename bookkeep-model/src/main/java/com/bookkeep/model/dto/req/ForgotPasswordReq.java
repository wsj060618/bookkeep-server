package com.bookkeep.model.dto.req;

import com.bookkeep.model.validation.EmailRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 申请重置密码（POST /api/auth/forgot-password）。
 * <p>邮箱现在是登录凭据且唯一（`uk_user_email_active`），所以只凭邮箱就能定位账号，
 * 不用再让用户记一个「用户名」。
 */
public record ForgotPasswordReq(

        @NotBlank(message = "邮箱不能为空")
        @Pattern(regexp = EmailRules.PATTERN, message = EmailRules.MESSAGE)
        @Size(max = 100, message = "邮箱不能超过 100 个字符")
        String email
) {
}