package com.bookkeep.service.mail;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * QQ 邮箱 SMTP 实现（smtp.qq.com:465，SSL，密码用的是「授权码」）。
 * <p>配置全部来自环境变量，见 application.yml 的 spring.mail.* 与 app.mail.from。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmtpPasswordResetMailer implements PasswordResetMailer {

    private final JavaMailSender mailSender;

    @Value("${app.mail.from:}")
    private String from;

    @Value("${spring.mail.username:}")
    private String username;

    @Override
    public boolean isConfigured() {
        return StringUtils.hasText(from) && StringUtils.hasText(username);
    }

    @Override
    public void send(String to, String username, String link, int ttlMinutes) {
        if (!isConfigured()) {
            log.warn("未配置发信（MAIL_USERNAME / MAIL_FROM），跳过重置邮件：to={}", mask(to));
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("班级记账 · 重置密码");
        message.setText("""
                你好 %s：

                有人（希望是你）申请重置「班级记账」的密码。点击下面的链接设置新密码：

                %s

                链接 %d 分钟内有效，且只能用一次。不是你本人操作就忽略这封邮件，密码不会变。
                """.formatted(username, link, ttlMinutes));
        try {
            mailSender.send(message);
            log.info("重置邮件已发送：to={}", mask(to));
        } catch (MailException e) {
            // 不能抛：500 会暴露「这个账号存在」，只记日志
            log.error("重置邮件发送失败：to={}，原因={}", mask(to), e.getMessage());
        }
    }

    /** 日志里不打印完整邮箱 */
    private static String mask(String email) {
        if (!StringUtils.hasText(email)) {
            return "-";
        }
        int at = email.indexOf('@');
        return at <= 1 ? "***" + email.substring(Math.max(at, 0)) : email.charAt(0) + "***" + email.substring(at);
    }
}