package com.bookkeep.model.dto.req;

import com.bookkeep.model.validation.EmailRules;
import com.bookkeep.model.validation.PasswordRules;
import com.bookkeep.model.validation.UsernameRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求体。只有一种账本类型（class），所以没有 bookType；
 * 分类模板由后端固定选取。
 */
public record RegisterReq(

        /** 显示名（不再是登录凭据）：只限长度，不超过 20 位 */
        @NotBlank(message = "用户名不能为空")
        @Size(max = UsernameRules.MAX, message = UsernameRules.MESSAGE)
        String username,

        @NotBlank(message = "密码不能为空")
        @Pattern(regexp = PasswordRules.PATTERN, message = PasswordRules.MESSAGE)
        String password,

        /** 登录凭据：必填且未删除行内唯一 */
        @NotBlank(message = "邮箱不能为空")
        @Pattern(regexp = EmailRules.PATTERN, message = EmailRules.MESSAGE)
        @Size(max = 100, message = "邮箱不能超过 100 个字符")
        String email,

        @Size(max = 50, message = "账本名不能超过 50 个字符")
        String bookName
) {
}