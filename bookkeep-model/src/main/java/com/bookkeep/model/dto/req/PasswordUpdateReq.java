package com.bookkeep.model.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改密码（PUT /api/users/me/password）。
 * <p>必须带旧密码：否则拿到手机的人可以直接改掉密码，把号变成自己的。
 * 新密码规则与注册一致（6–32 位）。
 */
public record PasswordUpdateReq(

        @NotBlank(message = "旧密码不能为空")
        String oldPassword,

        @NotBlank(message = "新密码不能为空")
        @Size(min = 6, max = 32, message = "密码长度需 6–32 位")
        String newPassword
) {
}