package com.bookkeep.web;

import com.bookkeep.model.dto.req.LoginReq;
import com.bookkeep.model.dto.req.RegisterReq;
import com.bookkeep.web.cli.ResetPasswordRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 改密码必须踢掉旧 token：{@code user.token_version} + JWT 里的 {@code ver} 一起钉住这件事。
 * <p>走真实链路（注册 → 登录 → 复用重置密码的同一个方法 → 旧 token 请求），
 * 不 mock 过滤器：要比对的正是「过滤器有没有因为版本不一致而拒绝认证」。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TokenInvalidationTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    private static final String OLD_PASSWORD = "old123456";
    private static final String NEW_PASSWORD = "new123456";
    private static final String ME = "/api/auth/me";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ResetPasswordRunner resetPasswordRunner;

    @Test
    @DisplayName("重置密码后：旧 token 立刻 401，用户表 token_version +1")
    void resetPasswordInvalidatesOldToken() throws Exception {
        String username = nextUsername();
        register(username);
        String oldToken = login(username, OLD_PASSWORD);

        assertEquals(0, tokenVersionOf(username));
        assertEquals(200, me(oldToken).path("code").asInt());

        assertNull(resetPasswordRunner.reset(username, NEW_PASSWORD));
        assertEquals(1, tokenVersionOf(username));

        // 旧 token 签名、有效期都没问题，只是版本对不上 → 过滤器不认证 → 真实 401
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + oldToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mockMvc.perform(get("/api/books").header(HttpHeaders.AUTHORIZATION, "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());

        // 旧密码不能再登录，新密码可以，并且新 token 正常可用
        loginExpect(username, OLD_PASSWORD, 400);
        String newToken = login(username, NEW_PASSWORD);
        assertEquals(200, me(newToken).path("code").asInt());
    }

    @Test
    @DisplayName("连续两次重置：版本累加，倒数第二个 token 也失效")
    void secondResetInvalidatesPreviousToken() throws Exception {
        String username = nextUsername();
        register(username);

        resetPasswordRunner.reset(username, NEW_PASSWORD);
        String firstNewToken = login(username, NEW_PASSWORD);
        resetPasswordRunner.reset(username, "third123456");

        assertEquals(2, tokenVersionOf(username));
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + firstNewToken))
                .andExpect(status().isUnauthorized());
        assertEquals(200, me(login(username, "third123456")).path("code").asInt());
    }

    // ---------- 辅助方法 ----------

    private int tokenVersionOf(String username) {
        return jdbcTemplate.queryForObject(
                "SELECT token_version FROM user WHERE username = ?", Integer.class, username);
    }

    private JsonNode me(String token) throws Exception {
        MvcResult result = mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String nextUsername() {
        return "tk" + SEQ.incrementAndGet();
    }

    private void register(String username) throws Exception {
        RegisterReq req = new RegisterReq(username, OLD_PASSWORD, null, null, "令牌账本");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginReq(username, password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        String token = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("token").asText();
        assertNotNull(token);
        return token;
    }

    private void loginExpect(String username, String password, int code) throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginReq(username, password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code));
    }
}