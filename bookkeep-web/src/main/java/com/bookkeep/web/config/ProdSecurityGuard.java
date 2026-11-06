package com.bookkeep.web.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;

/**
 * 公网启动前的硬检查：prod profile 下发现「还在用仓库里的默认密钥」就直接拒绝启动。
 * <p>为什么必须 fail-fast：JWT 密钥是唯一的签名凭据，默认值是公开的（写在 application.yml 里），
 * 忘了配环境变量就等于任何人都能伪造任意 user_id 的 token。别的配置错了只是功能异常，这一项错了是越权。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProdSecurityGuard {

    /** application.yml 里那个公开的默认值，出现它说明没配 JWT_SECRET */
    private static final String DEFAULT_SECRET = "bookkeep-jwt-secret-key-please-change-me-0123456789";

    private final Environment environment;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${spring.datasource.username:}")
    private String dbUser;

    @Value("${app.mail.from:}")
    private String mailFrom;

    @PostConstruct
    public void check() {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        if (!StringUtils.hasText(jwtSecret)
                || jwtSecret.equals(DEFAULT_SECRET)
                || jwtSecret.length() < 32) {
            throw new IllegalStateException(
                    "prod 环境必须设置 JWT_SECRET（≥32 字符，且不能使用仓库默认值），否则 token 可被伪造");
        }
        if ("root".equals(dbUser)) {
            log.warn("[安全] 生产环境正在使用 MySQL root 账号，建议改用只授权 bookkeep 库的专用账号");
        }
        if (!StringUtils.hasText(mailFrom)) {
            log.warn("[安全] MAIL_FROM 未配置：找回密码接口会返回成功但不发信");
        }
    }
}