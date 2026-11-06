package com.bookkeep.model.dto.req;

import jakarta.validation.constraints.Email;
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
        @Size(min = 4, max = 20, message = "用户名长度需为 4–20 位")
        @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母、数字和下划线")
        String username,

        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        @Size(max = 100, message = "邮箱不能超过 100 个字符")
        String email,

        /** 仅当 email 与当前值不同时才必填；服务端用 BCrypt 校验 */
        String password
) {
}