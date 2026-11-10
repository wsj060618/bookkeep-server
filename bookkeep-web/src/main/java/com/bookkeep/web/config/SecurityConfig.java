package com.bookkeep.web.config;

import com.bookkeep.common.util.JwtUtil;
import com.bookkeep.service.mapper.UserMapper;
import com.bookkeep.web.filter.JwtAuthenticationFilter;
import com.bookkeep.web.handler.JwtAccessDeniedHandler;
import com.bookkeep.web.handler.JwtAuthenticationEntryPoint;
import jakarta.servlet.DispatcherType;
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
 * 安全配置：注册/登录/找回密码/公示页放行，其余都要 token。
 * <p>不能写成 /api/auth/**，会把 /me 也放行。
 * <p>401/403 用真实状态码 + 同一套 JSON 信封，其余错误一律 200 + body.code。
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final JwtAccessDeniedHandler accessDeniedHandler;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable) // 无状态 + Bearer token，不需要 CSRF token
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // 不建 session
                .authorizeHttpRequests(auth -> auth
                        // 异步派发（导出的流式响应）与容器错误转发不再要求认证：
                        // 原始请求已经过一遍鉴权，ASYNC 阶段 SecurityContext 不会自动带过去，
                        // 不放过就会在「响应已提交」之后抛 401，客户端只能拿到半截文件
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/auth/register", "/api/auth/login",
                                "/api/auth/forgot-password", "/api/auth/reset-password").permitAll()
                        // 公示页：免登录只读
                        .requestMatchers("/api/public/report/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                // 手动 new，避免 Boot 把 Filter 再注册一遍
                .addFilterBefore(new JwtAuthenticationFilter(jwtUtil, userMapper), UsernamePasswordAuthenticationFilter.class)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
