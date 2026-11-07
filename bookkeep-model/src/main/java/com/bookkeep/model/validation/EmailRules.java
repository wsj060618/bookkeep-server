package com.bookkeep.model.validation;

/**
 * 邮箱格式的唯一口径：本地部分只允许 ASCII，域名必须带点且顶级域至少 2 位。
 * <p>Jakarta 的 {@code @Email} 太宽：`123@qq`、`a@b.c`、甚至 `张三@qq.com` 都能过，
 * 前端拦住了后端却不拦，两条路必须用同一个正则。
 */
public final class EmailRules {

    public static final String MESSAGE = "邮箱格式不正确";

    public static final String PATTERN =
            "^[A-Za-z0-9._%+-]+@(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$";

    private EmailRules() {
    }
}