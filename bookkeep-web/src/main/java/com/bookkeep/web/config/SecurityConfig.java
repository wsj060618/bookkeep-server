package com.bookkeep.web.config;

import com.bookkeep.common.util.JwtUtil;
import com.bookkeep.web.filter.JwtAuthenticationFilter;
import com.bookkeep.web.handler.JwtAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全配置（第 6 天起为正式版）。
 * <p>规则：{@code /api/auth/register}、{@code /api/auth/login} 放行，其余（含 {@code /api/auth/me}）都要 token。
 * <p>注意<strong>不能</strong>写成 {@code /api/auth/**}：那会把 {@code /api/auth/me} 也放行，
 * 匿名请求进去后 {@code SecurityUtil.getUserId()} 只能抛 401，等于把「是否登录」的判断散到业务层。
 * <p>过滤器手动 {@code new}：{@link JwtAuthenticationFilter} 不是 Bean，避免 Boot 把它额外注册到 Servlet 容器。
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtUtil jwtUtil;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable) // 无状态 + Bearer token，不需要 CSRF token
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // 不建 session
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/register", "/api/auth/login").permitAll() // 登录前唯一可访问的两个接口
                        .requestMatchers("/error").permitAll() // 容器错误转发也是过滤器链的一环，放行避免 401 盖掉真实错误
                        .anyRequest().authenticated()) // 其余全部需要认证
                .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint)) // 401 统一 JSON
                // 注册在 UsernamePasswordAuthenticationFilter 之前：Spring Security 的惯例位置，
                // 保证业务过滤器看到 SecurityContext 时已经填好
                .addFilterBefore(new JwtAuthenticationFilter(jwtUtil), UsernamePasswordAuthenticationFilter.class)
                .httpBasic(AbstractHttpConfigurer::disable) // 禁用 httpBasic
                .formLogin(AbstractHttpConfigurer::disable); // 禁用 formLogin
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
