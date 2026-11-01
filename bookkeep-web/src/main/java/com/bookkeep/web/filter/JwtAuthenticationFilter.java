package com.bookkeep.web.filter;

import com.bookkeep.common.util.JwtUtil;
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
 * JWT 认证过滤器：解析 {@code Authorization: Bearer <token>}，把 userId 放进 SecurityContext。
 * <p>只负责「认证」（你是谁），不负责「403 / 401 长什么样」：token 无效时不认证、继续走链，
 * 由 {@code JwtAuthenticationEntryPoint} 统一输出 JSON —— 401 只有一处实现，前端不用分辨两种错误格式。
 * <p><strong>故意不加 {@code @Component}</strong>：Boot 会把容器里所有 {@code Filter} 类型的 Bean
 * 自动注册到 Servlet 容器，那样这个过滤器会在安全过滤链之外再跑一遍。改由 {@code SecurityConfig} 手动 new。
 * <p>无状态：不写 session，每次请求都从 token 重新认证；用户注销（逻辑删除）不在这里查库，
 * 由用到用户数据的接口（如 {@code /api/auth/me}）自己校验，避免每个请求多一次查询。
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    /** 目前没有角色概念，先给一个占位角色，后续要分权限时再拆 */
    private static final List<SimpleGrantedAuthority> AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_USER"));

    private final JwtUtil jwtUtil;

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
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(
                    new UsernamePasswordAuthenticationToken(userId, null, AUTHORITIES));
            SecurityContextHolder.setContext(context);
        } catch (JwtException | IllegalArgumentException e) {
            // 过期 / 签名不对 / subject 不是数字：都当成「没登录」，不抛异常。
            // 这里降级为 debug：无效 token 是常见噪声（浏览器残留旧 token），打 info 会刷屏
            log.debug("token 无效，按未认证处理: {}", e.getMessage());
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
