package com.bookkeep.model.dto.req;

import com.bookkeep.model.validation.EmailRules;
import com.bookkeep.model.validation.UsernameRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 修改资料（PUT /api/users/me）：改显示名 / 登录邮箱。
 * <p>整体提交：username 和 email 都要带上（前端用当前值预填）。
 * <p>**改邮箱必须带当前密码**：邮箱是登录凭据，等于改登录名；只改 username 不需要密码。
 */
public record UserUpdateReq(

        @NotBlank(message = "用户名不能为空")
        @Size(min = UsernameRules.MIN, max = UsernameRules.MAX, message = UsernameRules.MESSAGE)
        @Pattern(regexp = UsernameRules.PATTERN, message = UsernameRules.MESSAGE)
        String username,

        @NotBlank(message = "邮箱不能为空")
        @Pattern(regexp = EmailRules.PATTERN, message = EmailRules.MESSAGE)
        @Size(max = 100, message = "邮箱不能超过 100 个字符")
        String email,

        /** 仅当 email 与当前值不同时才必填；服务端用 BCrypt 校验 */
        String password
) {
}