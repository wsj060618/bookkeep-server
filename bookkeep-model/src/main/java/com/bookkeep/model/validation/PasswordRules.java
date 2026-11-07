package com.bookkeep.model.validation;

/**
 * 密码的唯一口径：6–32 位，字母 / 数字 / 下划线中**至少含两种**。
 * <p>纯数字（123456）、纯字母（abcdef）、纯下划线都算弱口令，直接拒掉。
 * <p>只用于**设置新密码**（注册 / 改密码 / 重置密码）；登录、改邮箱、注销时提交的是旧密码，
 * 不能拿这条规则去校验，否则历史上带符号的密码会被判非法。
 */
public final class PasswordRules {

    public static final int MIN = 6;

    public static final int MAX = 32;

    public static final String MESSAGE = "密码需 6–32 位，字母/数字/下划线至少含两种";

    /** 三个负向断言分别排除「纯字母 / 纯数字 / 纯下划线」，比枚举组合可读 */
    public static final String PATTERN = "^(?![A-Za-z]{" + MIN + "," + MAX + "}$)(?![0-9]{"
            + MIN + "," + MAX + "}$)(?![_]{" + MIN + "," + MAX + "}$)[A-Za-z0-9_]{" + MIN + "," + MAX + "}$";

    private PasswordRules() {
    }
}
