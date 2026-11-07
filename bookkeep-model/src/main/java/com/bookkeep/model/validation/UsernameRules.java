package com.bookkeep.model.validation;

/**
 * 用户名（显示名）的唯一口径：只限长度，不超过 20 位，字符不设限。
 * <p>登录凭据是邮箱，用户名只是显示名，所以中文、空格、标点都允许；
 * 前端 `RegisterView` / `SettingsView` 只校验同一条长度规则。
 */
public final class UsernameRules {

    public static final int MAX = 20;

    public static final String MESSAGE = "用户名长度不能超过 20 位";

    private UsernameRules() {
    }
}
