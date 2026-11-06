package com.bookkeep.web;

import com.bookkeep.common.util.JwtUtil;
import com.bookkeep.model.dto.req.LoginReq;
import com.bookkeep.model.dto.req.RegisterReq;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 6 天认证链路集成测试：{@code SecurityConfig} + {@code JwtAuthenticationFilter} +
 * {@code JwtAuthenticationEntryPoint} + {@code GET /api/auth/me}。
 * <p>用真实 token（注册 → 登录 → 带 Bearer 请求），不用 {@code @WithMockUser}：
 * 后者的 principal 是 {@code User} 对象，而 {@code SecurityUtil} 只认 {@code Long}（token subject），
 * 也测不到过滤器是否真的填了 SecurityContext。
 * <p>类上 {@code @Transactional}：注册的数据在用例结束后回滚，MockMvc 与测试同线程所以接口内可见。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthMeTest {

    /** 用户名 4–20 位，靠自增避免撞唯一索引 */
    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    private static final String PASSWORD = "abc123456";

    private static final String ME = "/api/auth/me";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Test
    @DisplayName("无 token 访问 /api/auth/me：真实 HTTP 401 + code=401（不是默认的 403 空响应）")
    void noTokenReturns401() throws Exception {
        mockMvc.perform(get(ME))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录或 token 失效"));
    }

    @Test
    @DisplayName("token 不是合法 JWT：过滤器不认证，仍走 401")
    void garbageTokenReturns401() throws Exception {
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("token 被篡改（签名对不上）：401")
    void tamperedTokenReturns401() throws Exception {
        String username = nextUsername();
        register(username);
        String token = login(username);

        String[] parts = token.split("\\.");
        char[] payload = parts[1].toCharArray();
        payload[0] = payload[0] == 'e' ? 'f' : 'e';
        String tampered = parts[0] + "." + new String(payload) + "." + parts[2];

        assertFalse(jwtUtil.isValid(tampered));
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("Authorization 少了 Bearer 前缀：按未认证处理（协议要求 Bearer）")
    void missingBearerPrefixReturns401() throws Exception {
        String username = nextUsername();
        register(username);
        String token = login(username);

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("带有效 token：返回当前用户（含 currentBookId），绝不带 password")
    void validTokenReturnsCurrentUser() throws Exception {
        String username = nextUsername();
        register(username);
        String token = login(username);

        Long expectedUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM user WHERE username = ?", Long.class, username);
        assertEquals(expectedUserId, jwtUtil.parseUserId(token));

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(expectedUserId.intValue()))
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.currentBookId").isNumber())
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.deleted").doesNotExist());
    }

    @Test
    @DisplayName("两个用户各自的 token 拿到各自的资料（userId 来自 token，不来自请求参数）")
    void eachTokenMapsToItsOwnUser() throws Exception {
        String alice = nextUsername();
        String bob = nextUsername();
        register(alice);
        register(bob);
        String aliceToken = login(alice);
        String bobToken = login(bob);

        assertEquals(alice, me(aliceToken).path("data").path("username").asText());
        assertEquals(bob, me(bobToken).path("data").path("username").asText());
    }

    @Test
    @DisplayName("用户注销（逻辑删除）后旧 token 立即失效：selectById 被 MP 过滤掉 → code=401")
    void softDeletedUserTokenRejected() throws Exception {
        String username = nextUsername();
        register(username);
        String token = login(username);
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM user WHERE username = ?", Long.class, username);

        // 模拟注销：token 本身仍然合法（签名/时间都没问题），只是库里这个人被删了
        jdbcTemplate.update("UPDATE user SET deleted = 1 WHERE id = ?", userId);

        JsonNode body = me(token);
        // 这条路径抛的是 BusinessException(401)，由 GlobalExceptionHandler 包装成 HTTP 200 + body.code=401。
        // 第 7 天定稿的策略里这是<strong>有意</strong>的：真实 401 只表示「过滤器链判定未认证」，
        // 业务层的 401（用户已注销）交给前端拦截器按 code 处理（清 token + 跳登录），行为一致。
        assertEquals(401, body.path("code").asInt());
    }

    @Test
    @DisplayName("登录接口仍然匿名可访问：没有 token 也不会被 401 拦住")
    void loginStaysPublic() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400)); // 走到了参数校验，说明未被安全过滤链拦截
    }

    @Test
    @DisplayName("未实现的业务路径也要先认证：无 token 访问 /api/books 返回 401 而不是 404")
    void unknownBusinessPathRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/books"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    // ---------- 辅助方法 ----------

    /** 带 token 请求 /api/auth/me 并返回 JSON（响应体按 UTF-8 解码，否则中文提示乱码） */
    private JsonNode me(String token) throws Exception {
        MvcResult result = mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn();
        return json(result);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String nextUsername() {
        return "me" + SEQ.incrementAndGet();
    }

    private void register(String username) throws Exception {
        RegisterReq req = new RegisterReq(username, PASSWORD, username + "@example.com", "我的账本");
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private String login(String username) throws Exception {
        LoginReq req = new LoginReq(username + "@example.com", PASSWORD);
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        String token = json(result).path("data").path("token").asText();
        assertNotNull(token);
        return token;
    }
}
