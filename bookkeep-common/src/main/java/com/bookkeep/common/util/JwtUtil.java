package com.bookkeep.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtUtil {

    /** 令牌版本：与 user.token_version 比对，改密码后旧 token 立即作废 */
    private static final String CLAIM_TOKEN_VERSION = "ver";

    private final SecretKey key;
    private final long expireSeconds;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expire}") long expireSeconds) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireSeconds = expireSeconds;
    }

    /**
     * 生成token
     */
    public String generateToken(Long userId, int tokenVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_TOKEN_VERSION, tokenVersion)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expireSeconds)))
                .signWith(key)
                .compact();
    }

    /**
     * 解析token
     */
    public Long parseUserId(String token) {
        return Long.valueOf(claims(token).getSubject());
    }

    /**
     * 令牌版本：本次改动之前签发的老 token 没有该 claim，按 0 处理（用户记录默认也是 0，不会误杀）
     */
    public int parseTokenVersion(String token) {
        Object value = claims(token).get(CLAIM_TOKEN_VERSION);
        return value instanceof Number number ? number.intValue() : 0;
    }

    /**
     * 验证token
     */
    public boolean isValid(String token) {
        try {
            parseUserId(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims claims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}