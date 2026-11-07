package com.bookkeep.model.validation;

/**
 * 用户名（显示名）的唯一口径：2–20 位中文、字母、数字或下划线。
 * <p>登录凭据是邮箱，用户名只是显示名，所以允许中文；中文按 Han 脚本判，
 * 前端 `validate.ts` 用的是等价的 {@code \p{Script=Han}}，两边必须一致。
 */
public final class UsernameRules {

    public static final int MIN = 2;

    public static final int MAX = 20;

    public static final String MESSAGE = "用户名需 2–20 位中文、字母、数字或下划线";

    public static final String PATTERN = "^[\\p{IsHan}A-Za-z0-9_]+$";

    private UsernameRules() {
    }
}