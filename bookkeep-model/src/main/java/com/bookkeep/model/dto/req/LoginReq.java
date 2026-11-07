package com.bookkeep.model.dto.req;

import com.bookkeep.model.validation.EmailRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 登录请求体（POST /api/auth/login）。
 * <p>自「邮箱登录」改动起，登录凭据是**邮箱**（未删除行内唯一），不再是用户名——用户名只是显示名，可随时改。
 * <p>只校验「非空 + 邮箱格式」：密码对不对只由 {@code PasswordEncoder.matches} 判定。
 *
 * @param email    注册时填的邮箱
 * @param password 明文密码，只用于和库里的 BCrypt 哈希比对，绝不落库、不打日志（{@code LogAspect} 会脱敏）
 */
public record LoginReq(

        @NotBlank(message = "邮箱不能为空")
        @Pattern(regexp = EmailRules.PATTERN, message = EmailRules.MESSAGE)
        String email,

        @NotBlank(message = "密码不能为空")
        String password
) {
}