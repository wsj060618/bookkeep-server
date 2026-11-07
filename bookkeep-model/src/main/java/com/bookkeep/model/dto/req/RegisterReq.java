package com.bookkeep.model.dto.req;

import com.bookkeep.model.validation.EmailRules;
import com.bookkeep.model.validation.UsernameRules;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求体（POST /api/auth/register）
 * <p>注册即建账本：这个项目只有一种账本（班级账本，{@code book.type = class}），
 * 所以请求里<strong>没有</strong> bookType 字段；分类模板由后端按 class 固定选取。
 * <p>用 record 承载，校验注解直接标在组件上；校验失败由 {@code GlobalExceptionHandler}
 * 统一转成 {@code {code:400, message:"字段: 提示"}}。
 *
 * @param username 显示名，唯一，≤20 位（字符不限）
 * @param password 明文密码，6–32 位，落库前由 BCrypt 加密
 * @param email    登录凭据：必填、未删除行内唯一，不发验证邮件（见 PLAN §9）
 * @param bookName 账本名，可空；为空时默认「班级账本」
 */
//public record RegisterReq(String username, String password, ...) { }
//等价于：
//public final class RegisterReq extends java.lang.Record {
//        private final String username;
//        private final String password;
//        // ...
//
//        public RegisterReq(String username, String password, ...) {   // 全参构造
//                this.username = username;
//                this.password = password;
//        }
//
//        public String username() { return username; }   // 注意：没有 get 前缀
//        public String password() { return password; }
//
//        @Override public boolean equals(Object o) { ... }   // 按字段值比较
//        @Override public int hashCode() { ... }
//        @Override public String toString() { ... }          // RegisterReq[username=..., password=...]
//}
public record RegisterReq(

        /** 显示名（不再是登录凭据）：只限长度，不超过 20 位 */
        @NotBlank(message = "用户名不能为空")
        @Size(max = UsernameRules.MAX, message = UsernameRules.MESSAGE)
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 32, message = "密码长度需为 6–32 位")
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