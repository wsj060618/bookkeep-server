package com.bookkeep.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 安全配置（临时版）。
 * <p>第 3 天先全部放行，让第 4–5 天的注册 / 登录接口在认证体系完成前就能调通；
 * 第 6 天加 {@code JwtAuthenticationFilter} 后收紧为「放行 /api/auth/**，其余需认证」。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable) // 关跨站请求伪造防护
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)) // 无状态
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()) // 全部放行
                .httpBasic(AbstractHttpConfigurer::disable) // 禁用 httpBasic
                .formLogin(AbstractHttpConfigurer::disable); // 禁用 formLogin
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
