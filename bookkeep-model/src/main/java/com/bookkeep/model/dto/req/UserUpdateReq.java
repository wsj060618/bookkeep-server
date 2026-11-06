package com.bookkeep.model.dto.req;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * 修改资料（PUT /api/users/me）：只允许改昵称和邮箱，用户名不可改。
 * <p>两个字段都可空：只传一个就只改一个；传空串会被服务端转成 null（清空）。
 */
public record UserUpdateReq(

        @Size(max = 50, message = "昵称不能超过 50 个字符")
        String nickname,

        @Email(message = "邮箱格式不正确")
        @Size(max = 100, message = "邮箱不能超过 100 个字符")
        String email
) {
}