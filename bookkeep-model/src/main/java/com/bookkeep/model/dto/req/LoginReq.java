package com.bookkeep.model.dto.req;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求体（POST /api/auth/login）
 * <p>只校验「非空」：用户名格式和密码长度都由注册时的规则决定，
 * 登录再套一遍 {@code @Size}/{@code @Pattern} 只会让「以后放宽或收紧注册规则」变成
 * 「老用户登不进来」——密码对不对只由 {@code PasswordEncoder.matches} 判定。
 *
 * @param username 登录名
 * @param password 明文密码，只用于和库里的 BCrypt 哈希比对，绝不落库、不打日志（{@code LogAspect} 会脱敏）
 */
public record LoginReq(

        @NotBlank(message = "用户名不能为空")
        String username,

        @NotBlank(message = "密码不能为空")
        String password
) {
}
