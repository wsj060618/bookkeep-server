package com.bookkeep.web.config;

import com.bookkeep.common.util.JwtUtil;
import com.bookkeep.web.filter.JwtAuthenticationFilter;
import com.bookkeep.web.handler.JwtAccessDeniedHandler;
import com.bookkeep.web.handler.JwtAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全配置（第 7 天起为正式版）。
 * <p>规则：{@code /api/auth/register}、{@code /api/auth/login}、{@code /api/public/report/**} 放行，
 * 其余（含 {@code /api/auth/me}）都要 token。
 * <p>注意<strong>不能</strong>写成 {@code /api/auth/**}：那会把 {@code /api/auth/me} 也放行，
 * 匿名请求进去后 {@code SecurityUtil.getUserId()} 只能抛 401，等于把「是否登录」的判断散到业务层。
 * <p>过滤器手动 {@code new}：{@link JwtAuthenticationFilter} 不是 Bean，避免 Boot 把它额外注册到 Servlet 容器。
 * <p>401 / 403 都输出「真实状态码 + 同一套 JSON 信封」：401 由 {@link JwtAuthenticationEntryPoint} 写，
 * 403 由 {@link JwtAccessDeniedHandler} 写。这两个处理器是仅有的「状态码 ≠ 200」的出口，
 * 业务错误一律 200 + body.code（见 {@code GlobalExceptionHandler}）。
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtUtil jwtUtil;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final JwtAccessDeniedHandler accessDeniedHandler;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable) // 无状态 + Bearer token，不需要 CSRF token
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // 不建 session
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/register", "/api/auth/login").permitAll() // 登录前唯一可访问的两个接口
                        // 公示页：同学看账不需要账号，这是公网唯一放行的只读入口（PLAN §5.9）
                        .requestMatchers("/api/public/report/**").permitAll()
                        .requestMatchers("/error").permitAll() // 容器错误转发也是过滤器链的一环，放行避免 401 盖掉真实错误
                        .anyRequest().authenticated()) // 其余全部需要认证
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint) // 401 统一 JSON
                        .accessDeniedHandler(accessDeniedHandler)) // 403 统一 JSON（默认是 HTML 错误页）
                // 注册在 UsernamePasswordAuthenticationFilter 之前：Spring Security 的惯例位置，
                // 保证业务过滤器看到 SecurityContext 时已经填好
                .addFilterBefore(new JwtAuthenticationFilter(jwtUtil), UsernamePasswordAuthenticationFilter.class)
                .httpBasic(AbstractHttpConfigurer::disable) // 禁用 httpBasic
                .formLogin(AbstractHttpConfigurer::disable); // 禁用 formLogin
        return http.build();
    }
}
