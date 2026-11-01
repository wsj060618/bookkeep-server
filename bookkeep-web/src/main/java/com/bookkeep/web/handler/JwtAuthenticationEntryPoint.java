package com.bookkeep.web.handler;

import com.bookkeep.common.result.Result;
import com.bookkeep.common.result.ResultCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 未认证入口：无 token / token 无效时返回 <strong>真实 HTTP 401</strong> + {@code {code:401,...}}。
 * <p>为什么要单独写：{@code @RestControllerAdvice} 只能处理进入 DispatcherServlet 之后的异常，
 * 而「没登录」是在过滤器链里被拒的，走不到全局异常处理。不配这个 EntryPoint 时 Spring 默认用
 * {@code Http403ForbiddenEntryPoint}，无 token 会返回 403 —— 语义就错了。
 * <p>这是「HTTP 状态码 = code」的唯一例外之一：业务错误仍是 200 + body.code（见 PLAN 第 7 天决策），
 * 只有认证/授权失败用真实状态码，方便前端拦截器统一跳登录页。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        log.warn("未认证访问: {} {}", request.getMethod(), request.getRequestURI());
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), Result.error(ResultCode.UNAUTHORIZED));
    }
}
