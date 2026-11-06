package com.bookkeep.web;

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
import org.springframework.transaction.annotation.Transactional;

import static com.bookkeep.web.support.AuthHelper.getJson;
import static com.bookkeep.web.support.AuthHelper.loginData;
import static com.bookkeep.web.support.AuthHelper.nextUsername;
import static com.bookkeep.web.support.AuthHelper.registerAndLogin;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 账本增删改查 + 回收站（PLAN §5.2）。
 * <p>重点验证四件事：新建账本自带 13 条分类、切换后数据真的隔离、删除后 15 天内可恢复、
 * 到期后账本连同分类与流水被物理删除。
 * <p>用真实 token；{@code @Transactional} 保证测试数据回滚，包括回收站里的物理删除。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BookManageApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("新建账本：自动切为当前、自带 13 条分类、数据与旧账本隔离")
    void createSwitchesCurrentAndSeedsCategories() throws Exception {
        String username = nextUsername("book");
        String token = registerAndLogin(mockMvc, objectMapper, username);
        long firstBookId = bookId(token, 0);

        // 在旧账本里记一笔，用于验证切换后的隔离（记收入：空账本不能先记支出，余额会变负）
        long firstCategoryId = categoryId(token, "收取班费");
        mockMvc.perform(post("/api/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":" + firstCategoryId + ",\"type\":\"income\","
                                + "\"amount\":\"100.00\",\"transDate\":\"2026-11-01\",\"note\":\"旧账本\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 新建第二本：应自动成为当前账本
        mockMvc.perform(post("/api/books")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"二班账本\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("二班账本"))
                .andExpect(jsonPath("$.data.current").value(true))
                // 新建账本也不再送公示链接：必须用户点「分享」才生成
                .andExpect(jsonPath("$.data.shareToken").value(nullValue()));

        JsonNode books = getJson(mockMvc, objectMapper, "/api/books", token).path("data");
        assertEquals(2, books.size());
        assertEquals(firstBookId, books.get(0).path("id").asLong());
        assertEquals(false, books.get(0).path("current").asBoolean());
        assertEquals(true, books.get(1).path("current").asBoolean());

        // 新账本的分类是独立的一套（13 条），流水是空的
        JsonNode categories = getJson(mockMvc, objectMapper, "/api/categories", token).path("data");
        assertEquals(13, categories.size());
        JsonNode transactions = getJson(mockMvc, objectMapper, "/api/transactions", token).path("data");
        assertEquals(0, transactions.path("total").asInt());

        // 切回第一本：上一笔流水又能看到
        mockMvc.perform(put("/api/books/" + firstBookId + "/current")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].current").value(true))
                .andExpect(jsonPath("$.data[1].current").value(false));

        JsonNode back = getJson(mockMvc, objectMapper, "/api/transactions", token).path("data");
        assertEquals(1, back.path("total").asInt());
    }

    @Test
    @DisplayName("删除进回收站 → 列表消失、回收站出现且带到期时间 → 恢复回来")
    void deleteGoesToTrashAndCanBeRestored() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("book"));
        long firstBookId = bookId(token, 0);
        mockMvc.perform(post("/api/books")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"待删账本\"}"))
                .andExpect(status().isOk());
        long secondBookId = bookId(token, 1);

        mockMvc.perform(delete("/api/books/" + secondBookId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(1, getJson(mockMvc, objectMapper, "/api/books", token).path("data").size());

        JsonNode trash = getJson(mockMvc, objectMapper, "/api/books/trash", token).path("data");
        assertEquals(1, trash.size());
        assertEquals("待删账本", trash.get(0).path("name").asText());
        assertTrue(trash.get(0).path("deletedAt").asText().length() > 0);
        assertTrue(trash.get(0).path("expireAt").asText().length() > 0);

        mockMvc.perform(put("/api/books/" + secondBookId + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("待删账本"));

        assertEquals(2, getJson(mockMvc, objectMapper, "/api/books", token).path("data").size());
        assertEquals(0, getJson(mockMvc, objectMapper, "/api/books/trash", token).path("data").size());
        assertEquals(firstBookId, bookId(token, 0));
    }

    @Test
    @DisplayName("删除当前账本 → 当前账本自动重指到剩下那本")
    void deletingCurrentBookRepointsToAnother() throws Exception {
        String username = nextUsername("book");
        String token = registerAndLogin(mockMvc, objectMapper, username);
        long firstBookId = bookId(token, 0);

        mockMvc.perform(post("/api/books")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"二班\"}"))
                .andExpect(status().isOk());
        long secondBookId = bookId(token, 1);

        // 当前是第二本，删它
        mockMvc.perform(delete("/api/books/" + secondBookId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        JsonNode books = getJson(mockMvc, objectMapper, "/api/books", token).path("data");
        assertEquals(1, books.size());
        assertEquals(firstBookId, books.get(0).path("id").asLong());
        assertEquals(true, books.get(0).path("current").asBoolean());
    }

    @Test
    @DisplayName("只剩一本时不能删：400，账本还在")
    void cannotDeleteTheLastBook() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("book"));
        long onlyBookId = bookId(token, 0);

        mockMvc.perform(delete("/api/books/" + onlyBookId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("至少保留一本账本，先新建一本再删这本"));

        assertEquals(1, getJson(mockMvc, objectMapper, "/api/books", token).path("data").size());
    }

    @Test
    @DisplayName("别人的账本：改名 / 切换 / 删除 / 恢复 一律 404")
    void otherUsersBooksAreInvisible() throws Exception {
        String tokenA = registerAndLogin(mockMvc, objectMapper, nextUsername("booka"));
        String usernameB = nextUsername("bookb");
        registerAndLogin(mockMvc, objectMapper, usernameB);
        long bookIdB = loginData(mockMvc, objectMapper, usernameB).path("books").get(0).path("id").asLong();

        mockMvc.perform(put("/api/books/" + bookIdB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"越权改名\"}"))
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(put("/api/books/" + bookIdB + "/current")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(delete("/api/books/" + bookIdB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(put("/api/books/" + bookIdB + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(jsonPath("$.code").value(404));

        // B 的账本毫发无损
        assertEquals(1, loginData(mockMvc, objectMapper, usernameB).path("books").size());
        assertEquals("班级账本", loginData(mockMvc, objectMapper, usernameB)
                .path("books").get(0).path("name").asText());
    }

    @Test
    @DisplayName("回收站满 15 天：下次访问列表时账本连同分类与流水被物理删除")
    void expiredBooksArePurgedWithTheirData() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("book"));
        mockMvc.perform(post("/api/books")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"过期账本\"}"))
                .andExpect(status().isOk());
        long doomedBookId = bookId(token, 1);

        // 先记一笔，确保流水也会被清掉（收入：新账本余额为 0，支出会被余额校验挡下）
        long categoryId = categoryId(token, "收取班费");
        mockMvc.perform(post("/api/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":" + categoryId + ",\"type\":\"income\","
                                + "\"amount\":\"5.50\",\"transDate\":\"2026-11-02\"}"))
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(delete("/api/books/" + doomedBookId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(200));

        // 把删除时间改成 16 天前，模拟「放满 15 天」
        jdbcTemplate.update("UPDATE book SET deleted_at = DATE_SUB(NOW(), INTERVAL 16 DAY) WHERE id = ?",
                doomedBookId);

        // 访问列表会顺手清理过期项
        int remaining = getJson(mockMvc, objectMapper, "/api/books", token).path("data").size();
        assertEquals(1, remaining);

        assertEquals(0, count("SELECT COUNT(*) FROM book WHERE id = ?", doomedBookId));
        assertEquals(0, count("SELECT COUNT(*) FROM category WHERE book_id = ?", doomedBookId));
        assertEquals(0, count("SELECT COUNT(*) FROM `transaction` WHERE book_id = ?", doomedBookId));
        assertEquals(0, getJson(mockMvc, objectMapper, "/api/books/trash", token).path("data").size());
    }

    @Test
    @DisplayName("立即彻底删除：只能删回收站里的账本，连同分类与流水一起消失")
    void purgeTrashedBookImmediately() throws Exception {
        String username = nextUsername("book");
        String token = registerAndLogin(mockMvc, objectMapper, username);

        mockMvc.perform(post("/api/books")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"立刻删掉\"}"))
                .andExpect(jsonPath("$.code").value(200));
        long doomedBookId = bookId(token, 1);

        long categoryId = categoryId(token, "收取班费");
        mockMvc.perform(post("/api/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":" + categoryId + ",\"type\":\"income\","
                                + "\"amount\":\"9.90\",\"transDate\":\"2026-11-03\"}"))
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(delete("/api/books/" + doomedBookId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(1, getJson(mockMvc, objectMapper, "/api/books/trash", token).path("data").size());

        mockMvc.perform(delete("/api/books/" + doomedBookId + "/permanent")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(0, getJson(mockMvc, objectMapper, "/api/books/trash", token).path("data").size());
        assertEquals(0, count("SELECT COUNT(*) FROM book WHERE id = ?", doomedBookId));
        assertEquals(0, count("SELECT COUNT(*) FROM category WHERE book_id = ?", doomedBookId));
        assertEquals(0, count("SELECT COUNT(*) FROM `transaction` WHERE book_id = ?", doomedBookId));
    }

    @Test
    @DisplayName("立即彻底删除：往用账本 404；别人的回收站账本也 404")
    void purgeRejectsLiveOrOthersBook() throws Exception {
        String usernameA = nextUsername("booka");
        String tokenA = registerAndLogin(mockMvc, objectMapper, usernameA);
        long liveBookId = bookId(tokenA, 0);

        // 活着的不在回收站 → 不允许彻底删除
        mockMvc.perform(delete("/api/books/" + liveBookId + "/permanent")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
        assertEquals(1, count("SELECT COUNT(*) FROM book WHERE id = ? AND deleted = 0", liveBookId));

        // B 删一本进回收站，A 不能动手
        String usernameB = nextUsername("bookb");
        String tokenB = registerAndLogin(mockMvc, objectMapper, usernameB);
        mockMvc.perform(post("/api/books")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"B 的账本\"}"))
                .andExpect(jsonPath("$.code").value(200));
        long bookB = bookId(tokenB, 1);
        mockMvc.perform(delete("/api/books/" + bookB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(delete("/api/books/" + bookB + "/permanent")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
        assertEquals(1, count("SELECT COUNT(*) FROM book WHERE id = ? AND deleted = 1", bookB));
    }

    private long bookId(String token, int index) throws Exception {
        JsonNode books = getJson(mockMvc, objectMapper, "/api/books", token).path("data");
        return books.get(index).path("id").asLong();
    }

    private long categoryId(String token, String name) throws Exception {
        for (JsonNode category : getJson(mockMvc, objectMapper, "/api/categories", token).path("data")) {
            if (name.equals(category.path("name").asText())) {
                return category.path("id").asLong();
            }
        }
        throw new IllegalStateException("分类不存在：" + name);
    }

    private int count(String sql, long id) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, id);
        return value == null ? 0 : value;
    }
}