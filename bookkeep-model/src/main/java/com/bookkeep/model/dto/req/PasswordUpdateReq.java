package com.bookkeep.model.dto.req;

import com.bookkeep.model.validation.PasswordRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 修改密码（PUT /api/users/me/password）。
 * <p>必须带旧密码：否则拿到手机的人可以直接改掉密码，把号变成自己的。
 * <p>只有**新密码**受规则约束；旧密码是历史值，可能含符号，不能一起校验。
 */
public record PasswordUpdateReq(

        @NotBlank(message = "旧密码不能为空")
        String oldPassword,

        @NotBlank(message = "新密码不能为空")
        @Pattern(regexp = PasswordRules.PATTERN, message = PasswordRules.MESSAGE)
        String newPassword
) {
}
