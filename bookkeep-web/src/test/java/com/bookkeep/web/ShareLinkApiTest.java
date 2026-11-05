package com.bookkeep.web;

import com.bookkeep.web.support.AuthHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import static com.bookkeep.web.support.AuthHelper.nextUsername;
import static com.bookkeep.web.support.AuthHelper.registerAndLogin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公示链接生命周期：注册/新建账本不发链接，点「分享」才生成，有效期 3 天，再分享即换新 token。
 * <p>过期不用改系统时间：直接 `UPDATE book SET share_token_expires_at = 过去` 就行，
 * 这也正是「过期判断在 SQL 里」的好处。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ShareLinkApiTest {

    private static final int SHARE_DAYS = 3;

    private static final DateTimeFormatter JACKSON_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("分享：拿到 32 位 token + 3 天有效期；未分享前 books[].shareToken 为 null")
    void shareCreatesLinkWithThreeDayExpiry() throws Exception {
        String username = nextUsername("share");
        String token = registerAndLogin(mockMvc, objectMapper, username);
        long bookId = bookIdOf(username);

        JsonNode before = bookOf(username);
        assertTrue(before.path("shareToken").isNull(), "注册不应悄悄发一个永不过期的链接");

        LocalDateTime now = LocalDateTime.now();
        JsonNode share = json(mockMvc.perform(post("/api/books/" + bookId + "/share")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()).path("data");

        String shareToken = share.path("token").asText();
        assertEquals(32, shareToken.length(), "32 位 UUID 去横线");
        LocalDateTime expiresAt = LocalDateTime.parse(share.path("expiresAt").asText(), JACKSON_DATE_TIME);
        long seconds = ChronoUnit.SECONDS.between(now, expiresAt);
        assertTrue(Math.abs(seconds - SHARE_DAYS * 24 * 3600) < 120, "有效期应为 3 天，实际 " + seconds + " 秒");

        // 登录列表同步带上 token 与到期时间，前端刷新后还能显示当前链接
        JsonNode after = bookOf(username);
        assertEquals(shareToken, after.path("shareToken").asText());
        assertFalse(after.path("shareTokenExpiresAt").isNull());

        // 免登录能看
        mockMvc.perform(get("/api/public/report/" + shareToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.bookName").isString());
    }

    @Test
    @DisplayName("再次分享：旧链接立即 404，新链接可用")
    void reshareInvalidatesPreviousLink() throws Exception {
        String username = nextUsername("share");
        String token = registerAndLogin(mockMvc, objectMapper, username);
        long bookId = bookIdOf(username);

        String first = share(mockMvc, objectMapper, token, bookId);
        // 同一个 token 第二次点分享 → 必须拿到不一样的
        String second = share(mockMvc, objectMapper, token, bookId);
        assertFalse(first.equals(second), "每次分享都要换新 token");

        assertEquals(404, publicCode(first), "旧链接应立刻失效");
        assertEquals(200, publicCode(second));
    }

    @Test
    @DisplayName("链接过期：public 接口返回 404（business code，不是 500）")
    void expiredLinkIsRejected() throws Exception {
        String username = nextUsername("share");
        String token = registerAndLogin(mockMvc, objectMapper, username);
        long bookId = bookIdOf(username);
        String shareToken = share(mockMvc, objectMapper, token, bookId);

        assertEquals(200, publicCode(shareToken));
        jdbcTemplate.update("UPDATE book SET share_token_expires_at = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE id = ?", bookId);
        assertEquals(404, publicCode(shareToken));
    }

    @Test
    @DisplayName("别人的账本不能分享：404（不是 403，不泄露 id 是否存在）")
    void cannotShareOthersBook() throws Exception {
        String owner = nextUsername("shr");
        String other = nextUsername("shr");
        String ownerToken = registerAndLogin(mockMvc, objectMapper, owner);
        String otherToken = registerAndLogin(mockMvc, objectMapper, other);
        long ownerBookId = bookIdOf(owner);

        mockMvc.perform(post("/api/books/" + ownerBookId + "/share")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));

        // 对方没改成：owner 这边仍然是「还没分享」
        assertTrue(bookOf(owner).path("shareToken").isNull());
        assertEquals(32, share(mockMvc, objectMapper, ownerToken, ownerBookId).length());
    }

    @Test
    @DisplayName("未登录不能生成链接：真实 401")
    void shareRequiresLogin() throws Exception {
        String username = nextUsername("share");
        registerAndLogin(mockMvc, objectMapper, username);
        long bookId = bookIdOf(username);

        mockMvc.perform(post("/api/books/" + bookId + "/share"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    // ---------- 辅助方法 ----------

    private long bookIdOf(String username) throws Exception {
        return AuthHelper.loginData(mockMvc, objectMapper, username).path("books").get(0).path("id").asLong();
    }

    private JsonNode bookOf(String username) throws Exception {
        return AuthHelper.loginData(mockMvc, objectMapper, username).path("books").get(0);
    }

    private String share(MockMvc mockMvc, ObjectMapper objectMapper, String token, long bookId) throws Exception {
        return json(mockMvc.perform(post("/api/books/" + bookId + "/share")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn()).path("data").path("token").asText();
    }

    private int publicCode(String shareToken) throws Exception {
        return json(mockMvc.perform(get("/api/public/report/" + shareToken)).andReturn()).path("code").asInt();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}