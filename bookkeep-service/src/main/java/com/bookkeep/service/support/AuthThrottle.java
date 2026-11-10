package com.bookkeep.service.support;

import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录/注册的进程内限流（单实例，多实例部署要换共享存储）。
 * <p>prod 严格值在 application-prod.yml：同 IP+email 连续失败 5 次锁 5 分钟、
 * 单 IP 30 次/分钟登录、5 次/小时注册。
 */
@Component
public class AuthThrottle {

    private final int maxFailures;
    private final int ipPerMinute;
    private final int registerPerHour;

    private final Cache<String, AtomicInteger> failures;
    private final Cache<String, AtomicInteger> loginIpHits;
    private final Cache<String, AtomicInteger> registerIpHits;

    public AuthThrottle(@Value("${app.security.login-max-failures:5}") int maxFailures,
                        @Value("${app.security.login-ip-per-minute:100000}") int ipPerMinute,
                        @Value("${app.security.register-per-hour:100000}") int registerPerHour) {
        this.maxFailures = maxFailures;
        this.ipPerMinute = ipPerMinute;
        this.registerPerHour = registerPerHour;
        this.failures = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(5))
                .maximumSize(10_000)
                .build();
        this.loginIpHits = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(1))
                .maximumSize(10_000)
                .build();
        this.registerIpHits = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofHours(1))
                .maximumSize(10_000)
                .build();
    }

    /** 登录前检查：先看 IP 频率，再看这个 (ip,email) 的失败次数 */
    public void checkLogin(String ip, String email) {
        if (current(loginIpHits, safe(ip)) > ipPerMinute) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "操作过于频繁，请稍后再试");
        }
        if (current(failures, loginKey(ip, email)) >= maxFailures) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "密码错误次数过多，请 5 分钟后再试");
        }
    }

    public void recordLoginFailure(String ip, String email) {
        increment(loginIpHits, safe(ip));
        increment(failures, loginKey(ip, email));
    }

    public void resetLoginFailures(String ip, String email) {
        failures.invalidate(loginKey(ip, email));
    }

    /** 注册前检查：单 IP 每小时次数 */
    public void checkRegister(String ip) {
        if (current(registerIpHits, safe(ip)) >= registerPerHour) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "注册过于频繁，请稍后再试");
        }
    }

    /** 注册成功后计数（失败的重名/校验请求不占用额度） */
    public void recordRegister(String ip) {
        increment(registerIpHits, safe(ip));
    }

    /** 单测用：清空计数（限流器是单例，测试之间会串） */
    public void clear() {
        failures.invalidateAll();
        loginIpHits.invalidateAll();
        registerIpHits.invalidateAll();
    }

    private static String loginKey(String ip, String email) {
        return safe(ip) + "|" + (email == null ? "" : email.toLowerCase());
    }

    private static String safe(String value) {
        return StringUtils.hasText(value) ? value.trim() : "unknown";
    }

    private static int current(Cache<String, AtomicInteger> cache, String key) {
        AtomicInteger counter = cache.getIfPresent(key);
        return counter == null ? 0 : counter.get();
    }

    private static void increment(Cache<String, AtomicInteger> cache, String key) {
        cache.get(key, k -> new AtomicInteger()).incrementAndGet();
    }
}