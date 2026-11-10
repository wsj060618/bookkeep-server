package com.bookkeep.web.filter;

import com.bookkeep.common.util.JwtUtil;
import com.bookkeep.model.entity.User;
import com.bookkeep.service.mapper.UserMapper;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 解析 Bearer token，把 userId 放进 SecurityContext。
 * <p>只做认证，不写 401（无效 token 就不认证，交给 EntryPoint）。
 * <p>不能加 @Component：Boot 会把 Filter Bean 再注册一遍，由 SecurityConfig 手动 new。
 * <p>每个请求多一次 selectById，用于比对 token_version（改密码后旧 token 失效）。
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    /** 还没有角色概念，占位 */
    private static final List<SimpleGrantedAuthority> AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_USER"));

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        // 已有认证时不再覆盖：给后续过滤器（或测试里的 @WithMockUser）留出干预空间
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(token);
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(String token) {
        try {
            Long userId = jwtUtil.parseUserId(token);
            int tokenVersion = jwtUtil.parseTokenVersion(token);
            User user = userMapper.selectById(userId);
            if (user != null && user.getTokenVersion() != tokenVersion) {
                // 签发之后密码被改过（改密码 / 邮箱重置），这个 token 立即作废
                log.debug("token 版本已失效（密码被改过）：userId={}, tokenVer={}, dbVer={}",
                        userId, tokenVersion, user.getTokenVersion());
                return;
            }
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(
                    new UsernamePasswordAuthenticationToken(userId, null, AUTHORITIES));
            SecurityContextHolder.setContext(context);
        } catch (JwtException | IllegalArgumentException e) {
            // 过期 / 签名不对 / subject 不是数字：都当成「没登录」，不抛异常。
            // 这里降级到 debug：无效 token 是常见噪声（浏览器残留旧 token），打 info 会刷屏
            log.debug("token 无效，按未认证处理：{}", e.getMessage());
        }
    }

    /** 取 token：没有头、不是 Bearer、或 Bearer 后为空都返回 null（交由授权环节判 401） */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}