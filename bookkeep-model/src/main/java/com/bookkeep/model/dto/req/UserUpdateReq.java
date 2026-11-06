package com.bookkeep.model.dto.req;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改资料（PUT /api/users/me）：只允许改昵称和邮箱，用户名不可改。
 * <p>**邮箱必填**（它是找回密码的唯一通道），昵称可空（空串 = 清空，回显用户名）。
 * <p>注意是「整体提交」：前端要把当前昵称一起带上，否则会被当成清空。
 */
public record UserUpdateReq(

        @Size(max = 50, message = "昵称不能超过 50 个字符")
        String nickname,

        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        @Size(max = 100, message = "邮箱不能超过 100 个字符")
        String email
) {
}