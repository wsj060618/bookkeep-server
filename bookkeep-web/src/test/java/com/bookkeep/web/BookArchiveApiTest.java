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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static com.bookkeep.web.support.AuthHelper.categoryId;
import static com.bookkeep.web.support.AuthHelper.getJson;
import static com.bookkeep.web.support.AuthHelper.json;
import static com.bookkeep.web.support.AuthHelper.nextUsername;
import static com.bookkeep.web.support.AuthHelper.registerAndLogin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 学期归档（PLAN §10.5 方案 A）。
 * <p>核心契约：归档 = **只读**。记账 / 增删改分类 / 应用模板必须 400；
 * 改名、改截止日期、取消归档、导出、公示照常。用真实 token，{@code @Transactional} 保证回滚。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BookArchiveApiTest {

    private static final String ARCHIVED_MSG = "当前账本已归档（学期结束），只读；先取消归档再记账或改分类";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("新建账本可带学期截止日期；列表里原样返回")
    void createBookWithSemesterEnd() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("arc"));

        MvcResult created = mockMvc.perform(post("/api/books")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"秋季账本\",\"semesterEnd\":\"2026-12-31\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.semesterEnd").value("2026-12-31"))
                .andExpect(jsonPath("$.data.archived").value(false))
                .andReturn();
        long bookId = json(created, objectMapper).path("data").path("id").asLong();

        JsonNode books = getJson(mockMvc, objectMapper, "/api/books", token).path("data");
        JsonNode target = null;
        for (JsonNode book : books) {
            if (book.path("id").asLong() == bookId) {
                target = book;
            }
        }
        assertTrue(target != null, "新建的账本应该在列表里");
        assertEquals("2026-12-31", target.path("semesterEnd").asText());
    }

    @Test
    @DisplayName("归档后只读：记账 / 增删改分类 / 应用模板一律 400，读接口照常")
    void archivedBookIsReadOnly() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("arc"));
        long diningId = categoryId(mockMvc, objectMapper, token, "聚餐");
        long feeId = categoryId(mockMvc, objectMapper, token, "收取班费");

        // 先记一笔，确保归档前数据是正常的
        recordTransaction(token, feeId, "income", "100.00", "2026-09-01").andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(put("/api/books/" + bookId(token) + "/archive")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.archived").value(true));

        // 写：全被拦
        recordTransaction(token, feeId, "income", "50.00", "2026-09-02")
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(ARCHIVED_MSG));

        mockMvc.perform(post("/api/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"归档后新增\",\"type\":\"expense\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(ARCHIVED_MSG));

        mockMvc.perform(put("/api/categories/" + diningId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"聚餐改名\",\"type\":\"expense\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(ARCHIVED_MSG));

        mockMvc.perform(delete("/api/categories/" + diningId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(ARCHIVED_MSG));

        mockMvc.perform(post("/api/category-templates/0/apply")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(ARCHIVED_MSG));

        // 读：照常
        assertEquals(11, getJson(mockMvc, objectMapper, "/api/categories", token).path("data").size());
        assertEquals(200, getJson(mockMvc, objectMapper, "/api/transactions", token).path("code").asInt());
        assertEquals(200, getJson(mockMvc, objectMapper,
                "/api/statistics/summary?month=2026-09", token).path("code").asInt());

        // 导出照常（归档账本仍要能交接）：流式响应，走一遍异步派发
        MvcResult async = mockMvc.perform(get("/api/transactions/export")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(request().asyncStarted())
                .andReturn();
        String csv = mockMvc.perform(asyncDispatch(async))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\uFEFF"), "归档账本导出也要带 BOM");
        assertTrue(csv.contains("收取班费"));
    }

    @Test
    @DisplayName("归档后仍可改名 / 改截止日期，且不会把归档状态清掉")
    void renameAndSemesterEndStillAllowedWhenArchived() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("arc"));
        long id = bookId(token);

        mockMvc.perform(put("/api/books/" + id + "/archive")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.archived").value(true));

        mockMvc.perform(put("/api/books/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"2026-2027 秋季\",\"semesterEnd\":\"2027-01-15\"}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("2026-2027 秋季"))
                .andExpect(jsonPath("$.data.semesterEnd").value("2027-01-15"))
                .andExpect(jsonPath("$.data.archived").value(true));

        // 只改名、不传 semesterEnd：截止日期保持原值（null 表示「不改」，不是清空）
        mockMvc.perform(put("/api/books/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"再改个名\"}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.semesterEnd").value("2027-01-15"))
                .andExpect(jsonPath("$.data.archived").value(true));
    }

    @Test
    @DisplayName("取消归档 → 立刻能记账；重复归档/取消都是幂等的")
    void unarchiveRestoresWritable() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("arc"));
        long id = bookId(token);
        long feeId = categoryId(mockMvc, objectMapper, token, "收取班费");

        mockMvc.perform(put("/api/books/" + id + "/archive")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.archived").value(true));
        mockMvc.perform(put("/api/books/" + id + "/archive")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.archived").value(true));

        mockMvc.perform(put("/api/books/" + id + "/unarchive")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.archived").value(false));
        mockMvc.perform(put("/api/books/" + id + "/unarchive")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.archived").value(false));

        recordTransaction(token, feeId, "income", "888.00", "2026-09-03")
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("越权隔离：别人的账本不能归档（404）")
    void cannotArchiveOthersBook() throws Exception {
        String tokenA = registerAndLogin(mockMvc, objectMapper, nextUsername("arc"));
        String tokenB = registerAndLogin(mockMvc, objectMapper, nextUsername("arc"));

        mockMvc.perform(put("/api/books/" + bookId(tokenA) + "/archive")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));

        JsonNode booksA = getJson(mockMvc, objectMapper, "/api/books", tokenA).path("data");
        assertFalse(booksA.get(0).path("archived").asBoolean(), "A 的账本不该被 B 归档");
    }

    // ---------- 辅助方法 ----------

    private long bookId(String token) throws Exception {
        return getJson(mockMvc, objectMapper, "/api/books", token)
                .path("data").get(0).path("id").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions recordTransaction(
            String token, long categoryId, String type, String amount, String transDate) throws Exception {
        String body = "{\"categoryId\":" + categoryId + ",\"type\":\"" + type + "\","
                + "\"amount\":\"" + amount + "\",\"transDate\":\"" + transDate + "\"}";
        return mockMvc.perform(post("/api/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }
}