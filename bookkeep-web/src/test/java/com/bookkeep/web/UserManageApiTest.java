package com.bookkeep.web;

import com.bookkeep.model.dto.req.LoginReq;
import com.bookkeep.web.support.AuthHelper;
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
import java.sql.Timestamp;

import static com.bookkeep.web.support.AuthHelper.PASSWORD;
import static com.bookkeep.web.support.AuthHelper.nextUsername;
import static com.bookkeep.web.support.AuthHelper.registerAndLogin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户管理（PLAN §5.3）：改资料 / 改密码 / 注销。
 * <p>重点不是「接口返回 200」，而是三件副作用：
 * 改密码后旧 token 立刻失效；注销后同名可重新注册且账本进回收站；密码错的时候什么都不改。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserManageApiTest {

    private static final String NEW_PASSWORD = "newpass123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("改昵称/邮箱：/me 立刻反映；非法邮箱 → code=400")
    void updateProfile() throws Exception {
        String username = nextUsername("usr");
        String token = registerAndLogin(mockMvc, objectMapper, username);

        mockMvc.perform(put("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"新昵称\",\"email\":\"me@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.nickname").value("新昵称"))
                .andExpect(jsonPath("$.data.email").value("me@example.com"));

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.nickname").value("新昵称"))
                .andExpect(jsonPath("$.data.email").value("me@example.com"));

        mockMvc.perform(put("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("旧密码错：code=400，密码和 token 都不变")
    void wrongOldPasswordChangesNothing() throws Exception {
        String username = nextUsername("usr");
        String token = registerAndLogin(mockMvc, objectMapper, username);

        mockMvc.perform(put("/api/users/me/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"wrong-pass\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("旧密码不正确"));

        // 旧 token 仍然可用，旧密码仍然能登录
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(200, loginCode(username, PASSWORD));
        assertEquals(400, loginCode(username, NEW_PASSWORD));
    }

    @Test
    @DisplayName("改密码成功：旧 token 真实 401，新密码可登录")
    void passwordChangeInvalidatesOldToken() throws Exception {
        String username = nextUsername("usr");
        String token = registerAndLogin(mockMvc, objectMapper, username);

        mockMvc.perform(put("/api/users/me/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 版本不一致 → 过滤器不认证 → 真实 401（不是 200 + code=401）
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        assertEquals(1, tokenVersionOf(username));
        assertEquals(400, loginCode(username, PASSWORD));
        assertEquals(200, loginCode(username, NEW_PASSWORD));
    }

    @Test
    @DisplayName("注销时密码错：code=400，账号还在")
    void deleteWithWrongPasswordIsRejected() throws Exception {
        String username = nextUsername("usr");
        String token = registerAndLogin(mockMvc, objectMapper, username);

        mockMvc.perform(delete("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong-pass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        assertEquals(200, loginCode(username, PASSWORD));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT deleted FROM user WHERE username = ?", Integer.class, username));
    }

    @Test
    @DisplayName("注销成功：登录失效、旧 token 401、账本进回收站、同名可重新注册")
    void deleteAccount() throws Exception {
        String username = nextUsername("usr");
        String token = registerAndLogin(mockMvc, objectMapper, username);

        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM user WHERE username = ?", Long.class, username);
        Long bookId = jdbcTemplate.queryForObject(
                "SELECT id FROM book WHERE user_id = ? AND deleted = 0", Long.class, userId);

        mockMvc.perform(delete("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 账号已删：登录失败；旧 token 走业务层 401（HTTP 200，沿用既有策略）
        assertEquals(400, loginCode(username, PASSWORD));
        JsonNode me = json(mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn());
        assertEquals(401, me.path("code").asInt());

        // 数据不是删掉而是进回收站：deleted=1 且 deleted_at 有值
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT deleted FROM book WHERE id = ?", Integer.class, bookId));
        Timestamp deletedAt = jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM book WHERE id = ?", Timestamp.class, bookId);
        assertNotNull(deletedAt, "deleted_at 必须写，否则 15 天清理任务永远选不到它");

        // 用户名释放：同一名字可以重新注册（生成列 username_active 变 NULL）
        AuthHelper.register(mockMvc, objectMapper, username);
        assertEquals(200, loginCode(username, PASSWORD));
    }

    // ---------- 辅助方法 ----------

    private int loginCode(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginReq(username, password))))
                .andExpect(status().isOk())
                .andReturn();
        return json(result).path("code").asInt();
    }

    private int tokenVersionOf(String username) {
        return jdbcTemplate.queryForObject(
                "SELECT token_version FROM user WHERE username = ?", Integer.class, username);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}