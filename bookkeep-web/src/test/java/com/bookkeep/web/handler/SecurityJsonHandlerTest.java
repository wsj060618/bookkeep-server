package com.bookkeep.web.handler;

import com.bookkeep.common.result.ResultCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 401 / 403 两个响应处理器的单元测试（不起 Spring 上下文，毫秒级）。
 * <p>为什么不写成 {@code @SpringBootTest}：过滤器链里的 403 在 P1 没有真实可达路径
 * （还没有角色、没有 {@code @PreAuthorize}），集成测试造不出来；这里直接调用处理器，
 * 锁住「真实状态码 + 同一套 JSON 信封」这条前端依赖的契约。
 * <p>方法级鉴权触发的 403 走的是 {@code GlobalExceptionHandler} 委托同一个处理器，
 * 那条路径由 {@code GlobalExceptionHandlerTest.accessDenied} 覆盖。
 */
class SecurityJsonHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JwtAuthenticationEntryPoint entryPoint = new JwtAuthenticationEntryPoint(objectMapper);
    private final JwtAccessDeniedHandler accessDeniedHandler = new JwtAccessDeniedHandler(objectMapper);

    @Test
    @DisplayName("401 处理器：真实 HTTP 401 + {code:401}，不是默认的 403 空响应")
    void entryPointWrites401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(new MockHttpServletRequest("GET", "/api/auth/me"),
                response, new BadCredentialsException("bad token"));

        assertJson(response, 401, ResultCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("403 处理器：真实 HTTP 403 + {code:403}，不是默认的 HTML 错误页")
    void accessDeniedWrites403() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        accessDeniedHandler.handle(new MockHttpServletRequest("DELETE", "/api/books/1"),
                response, new AccessDeniedException("not the owner"));

        assertJson(response, 403, ResultCode.FORBIDDEN);
    }

    @Test
    @DisplayName("401 与 403 的 JSON 信封字段完全一致，前端只写一套解析逻辑")
    void bothShareTheSameEnvelope() throws Exception {
        MockHttpServletResponse unauthorized = new MockHttpServletResponse();
        entryPoint.commence(new MockHttpServletRequest("GET", "/api/auth/me"),
                unauthorized, new BadCredentialsException("bad token"));

        MockHttpServletResponse forbidden = new MockHttpServletResponse();
        accessDeniedHandler.handle(new MockHttpServletRequest("GET", "/api/auth/me"),
                forbidden, new AccessDeniedException("denied"));

        JsonNode u = objectMapper.readTree(unauthorized.getContentAsString(StandardCharsets.UTF_8));
        JsonNode f = objectMapper.readTree(forbidden.getContentAsString(StandardCharsets.UTF_8));

        assertEquals(fieldNames(u), fieldNames(f), "401 与 403 的信封字段必须一致");
        assertEquals(Set.of("code", "message", "data"), fieldNames(u));
        assertTrue(u.path("data").isNull(), "错误响应的 data 必须是 null，不能省略");
        assertTrue(f.path("data").isNull());
    }

    // ---------- 辅助方法 ----------

    private void assertJson(MockHttpServletResponse response, int expectedStatus, ResultCode expectedCode)
            throws Exception {
        assertEquals(expectedStatus, response.getStatus(), "HTTP 状态码必须是真实的 " + expectedStatus);
        assertTrue(response.getContentType().startsWith("application/json"),
                "Content-Type 必须是 JSON，实际: " + response.getContentType());

        JsonNode body = objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertEquals(expectedCode.getCode(), body.path("code").asInt());
        assertEquals(expectedCode.getMessage(), body.path("message").asText());
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return names;
    }
}