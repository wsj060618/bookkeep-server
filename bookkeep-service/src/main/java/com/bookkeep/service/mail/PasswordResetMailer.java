package com.bookkeep.service.mail;

/**
 * 找回密码邮件发送。
 * <p>抽成接口是为了测试能把它整个换掉（`@MockBean`），集成测试不碰真实 SMTP。
 */
public interface PasswordResetMailer {

    /** 发件人是否已配置（{@code MAIL_FROM} + SMTP 账号）；没配就不发，但接口仍返回 200 */
    boolean isConfigured();

    /**
     * 发送重置邮件。**实现内部必须吞掉发送异常**：抛出去会变成 500，
     * 等于告诉调用方「这个账号存在但发信失败」，破坏防枚举。
     */
    void send(String to, String username, String link, int ttlMinutes);
}