package com.bookkeep.web;

import com.bookkeep.model.dto.req.ForgotPasswordReq;
import com.bookkeep.model.dto.req.LoginReq;
import com.bookkeep.model.dto.req.ResetPasswordReq;
import com.bookkeep.service.mail.PasswordResetMailer;
import com.bookkeep.service.support.SimpleRateLimiter;
import com.bookkeep.web.support.AuthHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static com.bookkeep.web.support.AuthHelper.PASSWORD;
import static com.bookkeep.web.support.AuthHelper.nextUsername;
import static com.bookkeep.web.support.AuthHelper.registerAndLogin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 邮箱找回密码（PLAN §10.2）。
 * <p>mailer 用 {@code @MockBean} 换掉：集成测试不碰真实 SMTP，只断言「发了几封、链接里带什么 token」。
 * <p>限流器是单例，测试之间会串，所以每个用例先 clear。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PasswordResetApiTest {

    private static final String NEW_PASSWORD = "reset123456";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SimpleRateLimiter rateLimiter;

    @MockBean
    private PasswordResetMailer mailer;

    @BeforeEach
    void setUp() {
        rateLimiter.clear();
        reset(mailer);
        // 默认当作「SMTP 已配置」，具体用例再按需覆盖
        when(mailer.isConfigured()).thenReturn(true);
    }

    @Test
    @DisplayName("账号信息不匹配：仍然 200，但不发信（防枚举）")
    void unknownAccountSendsNothing() throws Exception {
        String username = nextUsername("rst");
        registerAndLogin(mockMvc, objectMapper, username);

        forgot("someone-else@example.com")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        verify(mailer, never()).send(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("邮箱格式不合法：直接 400 且不发信（`123@qq`、中文本地部分都不算邮箱）")
    void malformedEmailIsRejected() throws Exception {
        forgot("123@qq")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
        forgot("张三@qq.com")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        verify(mailer, never()).send(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("账号匹配：发一封邮件，链接里带 token，库里只存 hash")
    void knownAccountReceivesLink() throws Exception {
        String username = nextUsername("rst");
        registerAndLogin(mockMvc, objectMapper, username);
        String email = username + "@example.com";

        forgot(email)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(mailer).send(eq(email), eq(username), link.capture(), eq(15));
        String token = tokenOf(link.getValue());
        assertTrue(token.length() >= 40, "token 应当足够长：" + token.length());

        String stored = jdbcTemplate.queryForObject(
                "SELECT token_hash FROM password_reset_token WHERE user_id = "
                        + "(SELECT id FROM user WHERE username = ?)", String.class, username);
        assertEquals(64, stored.length(), "库里存 sha256 十六进制");
        assertTrue(!stored.contains(token), "库里绝不能出现明文 token");
    }

    @Test
    @DisplayName("用邮件里的 token 重置：旧 token 立刻 401、旧密码失效、同一链接不能再用")
    void resetWithToken() throws Exception {
        String username = nextUsername("rst");
        String oldToken = registerAndLogin(mockMvc, objectMapper, username);
        String email = username + "@example.com";

        forgot(email).andExpect(jsonPath("$.code").value(200));
        String token = tokenOf(capturedLink(email));

        submitReset(token, NEW_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 旧 JWT 因 token_version 变化被判失效 → 真实 401
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());

        assertEquals(400, loginCode(username, PASSWORD));
        assertEquals(200, loginCode(username, NEW_PASSWORD));

        // 同一个链接第二次用：400
        submitReset(token, "another12345")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("过期 token：400「链接已失效」")
    void expiredTokenRejected() throws Exception {
        String username = nextUsername("rst");
        registerAndLogin(mockMvc, objectMapper, username);

        forgot(username + "@example.com");
        String token = tokenOf(capturedLink(username + "@example.com"));

        jdbcTemplate.update("UPDATE password_reset_token SET expires_at = DATE_SUB(NOW(), INTERVAL 1 MINUTE) "
                + "WHERE user_id = (SELECT id FROM user WHERE username = ?)", username);

        submitReset(token, NEW_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("链接已失效，请重新申请"));
    }

    @Test
    @DisplayName("同一账号 1 小时内只发 3 封：第 4 次仍返回 200 但不发信")
    void rateLimitedPerAccount() throws Exception {
        String username = nextUsername("rst");
        registerAndLogin(mockMvc, objectMapper, username);
        String email = username + "@example.com";

        for (int i = 0; i < 4; i++) {
            forgot(email).andExpect(jsonPath("$.code").value(200));
        }

        verify(mailer, times(3)).send(eq(email), eq(username), anyString(), anyInt());
    }

    @Test
    @DisplayName("重新申请会作废旧 token：最早的链接立刻失效")
    void newRequestInvalidatesPreviousToken() throws Exception {
        String username = nextUsername("rst");
        registerAndLogin(mockMvc, objectMapper, username);
        String email = username + "@example.com";

        forgot(email);
        String first = tokenOf(capturedLink(email));
        forgot(email);
        String second = tokenOf(capturedLink(email));

        submitReset(first, NEW_PASSWORD)
                .andExpect(jsonPath("$.code").value(400));
        submitReset(second, NEW_PASSWORD)
                .andExpect(jsonPath("$.code").value(200));
    }

    // ---------- 辅助方法 ----------

    private org.springframework.test.web.servlet.ResultActions forgot(String email)
            throws Exception {
        ForgotPasswordReq req = new ForgotPasswordReq(email);
        return mockMvc.perform(post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)));
    }

    private org.springframework.test.web.servlet.ResultActions submitReset(String token, String password)
            throws Exception {
        return mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ResetPasswordReq(token, password))));
    }

    /** 取最近一次发给该邮箱的链接（多封时取最后一封） */
    private String capturedLink(String email) {
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(mailer, atLeastOnce()).send(eq(email), anyString(), link.capture(), anyInt());
        java.util.List<String> all = link.getAllValues();
        return all.get(all.size() - 1);
    }

    private static String tokenOf(String link) {
        int index = link.indexOf("token=");
        return link.substring(index + "token=".length());
    }

    private int loginCode(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginReq(AuthHelper.emailOf(username), password))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return body.path("code").asInt();
    }
}