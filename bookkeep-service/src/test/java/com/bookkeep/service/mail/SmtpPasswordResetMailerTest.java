package com.bookkeep.service.mail;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发件人显示名：MAIL_FROM 只放地址，MAIL_FROM_NAME 控制收件人看到的名字。
 * <p>不连真实 SMTP：mock JavaMailSender，只检查最终 MimeMessage 的 From 头。
 */
class SmtpPasswordResetMailerTest {

    @Test
    void fromHeaderUsesConfiguredDisplayName() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        SmtpPasswordResetMailer mailer = new SmtpPasswordResetMailer(sender);
        ReflectionTestUtils.setField(mailer, "from", "sender@qq.com");
        ReflectionTestUtils.setField(mailer, "fromName", "班级记账");
        ReflectionTestUtils.setField(mailer, "username", "sender@qq.com");

        mailer.send("to@example.com", "小明", "https://example.com/reset?token=x", 15);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        InternetAddress from = (InternetAddress) captor.getValue().getFrom()[0];
        assertEquals("sender@qq.com", from.getAddress());
        assertEquals("班级记账", from.getPersonal());
    }

    @Test
    void legacyFromWithNameIsStillAccepted() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));

        SmtpPasswordResetMailer mailer = new SmtpPasswordResetMailer(sender);
        ReflectionTestUtils.setField(mailer, "from", "旧名字 <sender@qq.com>");
        ReflectionTestUtils.setField(mailer, "fromName", "");
        ReflectionTestUtils.setField(mailer, "username", "sender@qq.com");

        mailer.send("to@example.com", "小明", "https://example.com/reset?token=x", 15);

        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        InternetAddress from = (InternetAddress) captor.getValue().getFrom()[0];
        assertEquals("sender@qq.com", from.getAddress());
        assertEquals("旧名字", from.getPersonal());
    }

    @Test
    void unconfiguredMailerDoesNotSend() {
        JavaMailSender sender = mock(JavaMailSender.class);
        SmtpPasswordResetMailer mailer = new SmtpPasswordResetMailer(sender);
        ReflectionTestUtils.setField(mailer, "from", "");
        ReflectionTestUtils.setField(mailer, "fromName", "班级记账");
        ReflectionTestUtils.setField(mailer, "username", "");

        mailer.send("to@example.com", "小明", "https://example.com/reset?token=x", 15);

        verify(sender, never()).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
    }
}