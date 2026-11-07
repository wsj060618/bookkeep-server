package com.bookkeep.service.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/**
 * QQ 邮箱 SMTP 实现（smtp.qq.com:465，SSL，密码用的是「授权码」）。
 * <p>配置全部来自环境变量，见 application.yml 的 spring.mail.* 与 app.mail.*。
 * <p>发件人显示名由 {@code app.mail.from-name} 控制；{@code MAIL_FROM} 只放纯地址。
 * 老配置写成 {@code "名字 <地址>"} 也兼容：会拆出地址，名字仅在未配置 from-name 时兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmtpPasswordResetMailer implements PasswordResetMailer {

    private final JavaMailSender mailSender;

    @Value("${app.mail.from:}")
    private String from;

    @Value("${app.mail.from-name:}")
    private String fromName;

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
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            applyFrom(helper);
            helper.setTo(to);
            helper.setSubject("班级记账 · 重置密码");
            helper.setText("""
                    你好 %s：

                    有人（希望是你）申请重置「班级记账」的密码。点击下面的链接设置新密码：

                    %s

                    链接 %d 分钟内有效，且只能用一次。不是你本人操作就忽略这封邮件，密码不会变。
                    """.formatted(username, link, ttlMinutes), false);
            mailSender.send(message);
            log.info("重置邮件已发送：to={}", mask(to));
        } catch (MailException | MessagingException | UnsupportedEncodingException e) {
            // 不能抛：500 会暴露「这个账号存在」，只记日志
            log.error("重置邮件发送失败：to={}，原因={}", mask(to), e.getMessage());
        }
    }

    /**
     * From 头 = 地址 + 显示名。MAIL_FROM 允许老格式「名字 &lt;地址&gt;」，这里统一拆成纯地址，
     * 避免 setFrom(地址, 名字) 拼出 &lt;名字 &lt;地址&gt;&gt; 这种非法头。
     */
    private void applyFrom(MimeMessageHelper helper) throws MessagingException, UnsupportedEncodingException {
        InternetAddress[] parsed = InternetAddress.parse(from);
        if (parsed.length == 0 || !StringUtils.hasText(parsed[0].getAddress())) {
            throw new MessagingException("MAIL_FROM 不是合法邮箱地址");
        }
        String address = parsed[0].getAddress();
        String personal = StringUtils.hasText(fromName) ? fromName : parsed[0].getPersonal();
        if (StringUtils.hasText(personal)) {
            helper.setFrom(address, personal);
        } else {
            helper.setFrom(address);
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