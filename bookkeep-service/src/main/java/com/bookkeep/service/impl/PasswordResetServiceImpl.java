package com.bookkeep.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.bookkeep.model.dto.req.ForgotPasswordReq;
import com.bookkeep.model.dto.req.ResetPasswordReq;
import com.bookkeep.model.entity.PasswordResetToken;
import com.bookkeep.model.entity.User;
import com.bookkeep.service.PasswordResetService;
import com.bookkeep.service.mail.PasswordResetMailer;
import com.bookkeep.service.mapper.PasswordResetTokenMapper;
import com.bookkeep.service.mapper.UserMapper;
import com.bookkeep.service.support.SimpleRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 邮箱找回密码实现。
 * <p>三条不能改的规则（PLAN §8 第 24 条）：
 * 1. 申请阶段永远不告诉调用方「账号是否存在」——限流、未配置、匹配失败都只是「不发信」
 * 2. 库里的 token 只存 sha256，明文只出现在邮件里
 * 3. 重置成功必须 `token_version + 1`，否则旧会话还能用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetServiceImpl implements PasswordResetService {

    /** 链接有效期：15 分钟 */
    public static final int TTL_MINUTES = 15;

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserMapper userMapper;
    private final PasswordResetTokenMapper tokenMapper;
    private final PasswordResetMailer mailer;
    private final PasswordEncoder passwordEncoder;
    private final SimpleRateLimiter rateLimiter;

    @Value("${app.base-url:http://localhost}")
    private String baseUrl;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void requestReset(ForgotPasswordReq req, String clientIp) {
        String username = req.username().trim();
        String email = req.email().trim();

        boolean withinPair = rateLimiter.tryAcquire(
                "forgot:" + username + "|" + email.toLowerCase(Locale.ROOT), 3, Duration.ofHours(1));
        boolean withinIp = rateLimiter.tryAcquire("forgot-ip:" + clientIp, 10, Duration.ofHours(1));
        if (!withinPair || !withinIp) {
            log.warn("找回密码触发限流：username={}, ip={}", username, clientIp);
            return;
        }

        // 用户名区分大小写、邮箱不区分：库里 username 是 as_cs，email 是 ai_ci，交给列排序规则比较
        User user = userMapper.selectOne(Wrappers.<User>query()
                .eq("username", username)
                .eq("email", email));
        if (user == null) {
            log.info("找回密码：账号信息不匹配，不发送（ip={}）", clientIp);
            return;
        }
        if (!mailer.isConfigured()) {
            log.warn("找回密码：未配置 SMTP，跳过发送（userId={}）", user.getId());
            return;
        }

        // 重新申请即作废旧 token：避免一个账号同时有多条可用链接
        tokenMapper.invalidateAll(user.getId());

        String rawToken = newToken();
        PasswordResetToken row = new PasswordResetToken();
        row.setUserId(user.getId());
        row.setTokenHash(sha256(rawToken));
        row.setExpiresAt(LocalDateTime.now().plusMinutes(TTL_MINUTES));
        tokenMapper.insert(row);

        String link = baseUrl + "/reset-password?token=" + rawToken;
        mailer.send(user.getEmail(), user.getUsername(), link, TTL_MINUTES);
        log.info("找回密码：已签发重置链接（userId={}）", user.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(ResetPasswordReq req) {
        PasswordResetToken row = tokenMapper.selectOne(Wrappers.<PasswordResetToken>query()
                .eq("token_hash", sha256(req.token().trim()))
                .isNull("used_at")
                .gt("expires_at", LocalDateTime.now()));
        if (row == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "链接已失效，请重新申请");
        }
        User user = userMapper.selectById(row.getUserId());
        if (user == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "链接已失效，请重新申请");
        }

        // 标记已用 + 作废该用户其它 token（"只能用一次" 由 used_at IS NULL 条件保证）
        if (tokenMapper.markUsed(row.getId()) == 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "链接已失效，请重新申请");
        }
        tokenMapper.invalidateAll(user.getId());

        userMapper.update(null, Wrappers.<User>update()
                .eq("id", user.getId())
                .set("password", passwordEncoder.encode(req.newPassword()))
                .setSql("token_version = token_version + 1"));

        log.info("邮箱重置密码成功：userId={}，旧 token 已全部失效", user.getId());
    }

    private static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}