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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 已认证但无权限：返回 <strong>真实 HTTP 403</strong> + {@code {code:403,...}}，信封与 401 完全一致。
 * <p>为什么需要它：不配 {@code AccessDeniedHandler} 时 Spring 用默认的 {@code AccessDeniedHandlerImpl}，
 * 返回一个 Tomcat 默认错误页（HTML），前端拦截器解析不了 JSON。
 * <p>与 {@link JwtAuthenticationEntryPoint} 的分工：401 = 不知道你是谁（过滤器链里
 * {@code ExceptionTranslationFilter} 判定未认证）；403 = 知道你是谁但权限不够。
 * <p>这是「HTTP 状态码 = code」的例外之二：业务错误仍是 200 + body.code（见 PLAN §5 / 第 7 天决策），
 * 只有认证/授权失败用真实状态码，前端据此清 token 或提示无权限。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        log.warn("无权限访问: {} {} - {}", request.getMethod(), request.getRequestURI(),
                accessDeniedException.getMessage());
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), Result.error(ResultCode.FORBIDDEN));
    }
}
