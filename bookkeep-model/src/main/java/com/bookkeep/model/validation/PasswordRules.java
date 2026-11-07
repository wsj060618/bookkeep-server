package com.bookkeep.model.validation;

/**
 * 密码的唯一口径：6–32 位字母、数字或下划线。
 * <p>只用于**设置新密码**（注册 / 改密码 / 重置密码）；登录、改邮箱、注销时提交的是旧密码，
 * 不能拿这条规则去校验，否则历史上带符号的密码会被判非法。
 */
public final class PasswordRules {

    public static final int MIN = 6;

    public static final int MAX = 32;

    public static final String MESSAGE = "密码需 6–32 位字母、数字或下划线";

    public static final String PATTERN = "^[A-Za-z0-9_]{" + MIN + "," + MAX + "}$";

    private PasswordRules() {
    }
}
