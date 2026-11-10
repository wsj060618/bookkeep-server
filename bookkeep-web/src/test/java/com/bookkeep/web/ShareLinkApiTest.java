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
 * <p>一条链接可以带**多本账本**（{@code share_link} + {@code share_link_book}），
 * 所以「过期」直接改 {@code share_link} 表就行，判断在 SQL 里。
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
    @DisplayName("分享：拿到 32 位 token + 3 天有效期；公示页能看到这本账本")
    void shareCreatesLinkWithThreeDayExpiry() throws Exception {
        String username = nextUsername("share");
        String token = registerAndLogin(mockMvc, objectMapper, username);
        long bookId = bookIdOf(username);

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

        // 免登录能看，且是按「链接里的账本」返回
        mockMvc.perform(get("/api/public/report/" + shareToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.books.length()").value(1))
                .andExpect(jsonPath("$.data.books[0].bookId").value(bookId))
                .andExpect(jsonPath("$.data.books[0].bookName").isString())
                .andExpect(jsonPath("$.data.expiresAt").isString());
    }

    @Test
    @DisplayName("一条链接带多本账本：分享弹窗勾几本，公示页就能看到几本（按勾选顺序）")
    void oneLinkCoversMultipleBooks() throws Exception {
        String username = nextUsername("share");
        String token = registerAndLogin(mockMvc, objectMapper, username);
        long first = bookIdOf(username);
        long second = createBook(token, "第二本");

        JsonNode share = json(mockMvc.perform(post("/api/books/share")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + second + "," + first + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.books.length()").value(2))
                .andReturn()).path("data");

        // 返回顺序 = 勾选顺序
        assertEquals(second, share.path("books").get(0).path("id").asLong());
        assertEquals(first, share.path("books").get(1).path("id").asLong());

        JsonNode books = json(mockMvc.perform(get("/api/public/report/" + share.path("token").asText()))
                .andReturn()).path("data").path("books");
        assertEquals(2, books.size());
        assertEquals(second, books.get(0).path("bookId").asLong());
        assertEquals("第二本", books.get(0).path("bookName").asText());
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
        jdbcTemplate.update("UPDATE share_link SET expires_at = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE token = ?", shareToken);
        assertEquals(404, publicCode(shareToken));
    }

    @Test
    @DisplayName("账本彻底删除后，链接里不再包含它；链接里的账本全没了则整条 404")
    void purgeBookRemovesItFromLink() throws Exception {
        String username = nextUsername("share");
        String token = registerAndLogin(mockMvc, objectMapper, username);
        long first = bookIdOf(username);
        long second = createBook(token, "第二本");
        String shareToken = json(mockMvc.perform(post("/api/books/share")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + first + "," + second + "]}"))
                .andReturn()).path("data").path("token").asText();

        // 删掉第二本（进回收站 → 彻底删除）
        mockMvc.perform(post("/api/books/batch-delete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + second + "]}"))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(post("/api/books/batch-purge")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + second + "]}"))
                .andExpect(jsonPath("$.code").value(200));

        JsonNode books = json(mockMvc.perform(get("/api/public/report/" + shareToken)).andReturn())
                .path("data").path("books");
        assertEquals(1, books.size(), "被彻底删除的账本不应再出现在链接里");
        assertEquals(first, books.get(0).path("bookId").asLong());
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

        // 对方没改成：owner 这边仍能正常分享
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

    private long createBook(String token, String name) throws Exception {
        return json(mockMvc.perform(post("/api/books")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andReturn()).path("data").path("id").asLong();
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