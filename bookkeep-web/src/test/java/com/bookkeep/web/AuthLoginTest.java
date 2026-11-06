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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录接口集成测试（POST /api/auth/login）
 * <p>造数据走真实的 {@code /register}，不手搓 BCrypt 哈希——注册和登录是同一套密码策略，
 * 手工造的用户反而可能和线上状态不一致。
 * <p>类上的 {@code @Transactional} 让每个用例结束后回滚；MockMvc 与测试同一线程，
 * 所以注册写进去的行在同一个事务里对登录可见。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthLoginTest {

    /** 用户名靠自增 + 时间戳避免撞唯一索引（长度 16，符合 4–20 限制） */
    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    private static final String PASSWORD = "abc123456";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtil jwtUtil;

    @Test
    @DisplayName("登录成功：返回 token + 用户信息 + 账本，token 能被 JwtUtil 解析回同一 userId")
    void loginSuccess() throws Exception {
        String username = nextUsername();
        register(username, "我的账本");

        MvcResult result = login(username, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.user.username").value(username))
                .andExpect(jsonPath("$.data.user.nickname").value(username))   // 注册时昵称留空 → 默认用户名
                .andExpect(jsonPath("$.data.user.currentBookId").isNumber())
                .andExpect(jsonPath("$.data.user.password").doesNotExist())    // 响应体绝不能带密码
                .andExpect(jsonPath("$.data.user.deleted").doesNotExist())
                .andExpect(jsonPath("$.data.books.length()").value(1))
                .andExpect(jsonPath("$.data.books[0].name").value("我的账本"))
                .andExpect(jsonPath("$.data.books[0].type").value("class"))
                // 公示链接改成「点分享才生成」：注册后应是没有链接的状态，有效期字段同为 null
                .andExpect(jsonPath("$.data.books[0].shareToken").value(nullValue()))
                .andExpect(jsonPath("$.data.books[0].shareTokenExpiresAt").value(nullValue()))
                .andExpect(jsonPath("$.data.books[0].current").value(true))
                .andReturn();

        // token 的 subject 必须是这个用户：第 6 天的 JwtAuthenticationFilter 完全依赖这一点
        JsonNode body = json(result);
        String token = body.path("data").path("token").asText();
        Long expectedUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM user WHERE username = ?", Long.class, username);
        assertEquals(expectedUserId, jwtUtil.parseUserId(token));
        assertTrue(jwtUtil.isValid(token));
    }

    @Test
    @DisplayName("密码错误：400 + 统一提示，且不返回 token")
    void wrongPasswordRejected() throws Exception {
        String username = nextUsername();
        register(username, "我的账本");

        login(username, "wrong-password")
                .andExpect(status().isOk())                        // 业务错误仍走 HTTP 200 + body.code
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("用户不存在：提示与密码错误完全相同（防用户名枚举）")
    void unknownUserMessageIsIdentical() throws Exception {
        String username = nextUsername();
        register(username, "我的账本");

        MvcResult wrongPassword = login(username, "wrong-password").andReturn();
        MvcResult unknownUser = login(nextUsername(), PASSWORD).andReturn();

        JsonNode a = json(wrongPassword);
        JsonNode b = json(unknownUser);
        assertEquals(a.path("code").asInt(), b.path("code").asInt());
        assertEquals(a.path("message").asText(), b.path("message").asText());
        assertEquals("用户名或密码错误", b.path("message").asText());
    }

    @Test
    @DisplayName("被逻辑删除的用户登不进来：MP 自动过滤 deleted=1，走同一条错误提示")
    void softDeletedUserRejected() throws Exception {
        String username = nextUsername();
        // 密码哈希随便给：这条记录根本查不出来，走的是「用户不存在」分支
        jdbcTemplate.update("INSERT INTO user (username, password, email, deleted) VALUES (?, ?, ?, 1)",
                username, "$2a$10$abcdefghijklmnopqrstuv", username + "@example.com");

        login(username, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    @DisplayName("参数校验失败：用户名 / 密码为空 → 400，提示来自 LoginReq 的 @NotBlank")
    void blankParamsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("不能为空")));
    }

    @Test
    @DisplayName("token 自洽：原样可解析，改动 payload 后失效")
    void tamperedTokenRejected() throws Exception {
        String username = nextUsername();
        register(username, "我的账本");

        MvcResult result = login(username, PASSWORD).andReturn();
        String token = json(result).path("data").path("token").asText();

        assertTrue(jwtUtil.isValid(token));

        // 动 payload 的第一个字符：要么签名校验失败，要么 base64 解出来不是合法 JSON，
        // 两者都是 JwtException，isValid 一律返回 false
        String[] parts = token.split("\\.");
        char[] payload = parts[1].toCharArray();
        payload[0] = payload[0] == 'e' ? 'f' : 'e';
        String tampered = parts[0] + "." + new String(payload) + "." + parts[2];

        assertNotEquals(token, tampered);
        assertFalse(jwtUtil.isValid(tampered));
    }

    // ---------- 辅助方法 ----------

    /**
     * 把响应体解析成 JSON。
     * <p>{@code MockHttpServletResponse.getContentAsString()} 在响应头没有 charset 时按
     * ISO-8859-1 解码（MockMvc 的默认 characterEncoding），而 JSON 实际是 UTF-8，
     * 直接用它读中文会得到「ç¨æ·å...」这种乱码，断言中文提示就必然失败。
     * 真实客户端按 JSON 规范用 UTF-8 解析，所以这是测试侧的问题，不是接口的问题。
     */
    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String nextUsername() {
        return "log" + SEQ.incrementAndGet();
    }

    private org.springframework.test.web.servlet.ResultActions register(String username, String bookName) throws Exception {
        RegisterReq req = new RegisterReq(username, PASSWORD, null, username + "@example.com", bookName);
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)));
    }

    private org.springframework.test.web.servlet.ResultActions login(String username, String password) throws Exception {
        LoginReq req = new LoginReq(username, password);
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)));
    }
}
