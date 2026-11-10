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

import java.util.List;

import static com.bookkeep.web.support.AuthHelper.categoryId;
import static com.bookkeep.web.support.AuthHelper.getJson;
import static com.bookkeep.web.support.AuthHelper.json;
import static com.bookkeep.web.support.AuthHelper.nextUsername;
import static com.bookkeep.web.support.AuthHelper.registerAndLogin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批量操作：流水 / 分类 / 账本 / 回收站的勾选删除，以及「分享弹窗」的批量出链接。
 * <p>共同契约：**要么全成功、要么整批拒绝**（不做「删一半」）；批量分享要复用未过期的旧链接。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BatchApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("流水批量删除：勾两条删两条；混进别人的 id 整批 400 且一条都不删")
    void batchDeleteTransactions() throws Exception {
        String tokenA = registerAndLogin(mockMvc, objectMapper, nextUsername("bat"));
        String tokenB = registerAndLogin(mockMvc, objectMapper, nextUsername("bat"));
        long feeId = categoryId(mockMvc, objectMapper, tokenA, "收取班费");

        long tx1 = recordTransaction(tokenA, feeId, "100.00", "2026-09-01");
        long tx2 = recordTransaction(tokenA, feeId, "200.00", "2026-09-02");
        long tx3 = recordTransaction(tokenA, feeId, "300.00", "2026-09-03");

        // 先混入一条别人的流水：整批拒绝，A 的三条都还在
        String bodyWithForeign = "{\"ids\":[" + tx1 + "," + tx2 + ",999999]}";
        mockMvc.perform(post("/api/transactions/batch-delete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithForeign))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
        assertEquals(3, getJson(mockMvc, objectMapper, "/api/transactions", tokenA)
                .path("data").path("total").asInt(), "整批拒绝时不能删掉任何一条");

        // 正常删两条
        mockMvc.perform(post("/api/transactions/batch-delete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + tx1 + "," + tx3 + "]}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(2));
        assertEquals(1, getJson(mockMvc, objectMapper, "/api/transactions", tokenA)
                .path("data").path("total").asInt());

        // B 一条都删不到
        mockMvc.perform(post("/api/transactions/batch-delete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + tx2 + "]}"))
                .andExpect(jsonPath("$.code").value(400));
        assertEquals(1, getJson(mockMvc, objectMapper, "/api/transactions", tokenA)
                .path("data").path("total").asInt());
    }

    @Test
    @DisplayName("分类批量删除：没流水的整批删掉；有一条被引用就整批 400（提示是哪条）")
    void batchDeleteCategories() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("bat"));
        long unusedA = createCategory(token, "白板笔");
        long unusedB = createCategory(token, "投影仪");

        mockMvc.perform(post("/api/categories/batch-delete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + unusedA + "," + unusedB + "]}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(2));
        JsonNode left = getJson(mockMvc, objectMapper, "/api/categories", token).path("data");
        assertEquals(11, left.size(), "删掉两个自建分类后应回到 11 条");

        // 记一笔挂到「收取班费」（收入分类，测试助手只记收入），然后尝试把它和另一个没流水的分类一起删 → 整批拒绝
        long feeId = categoryId(mockMvc, objectMapper, token, "收取班费");
        long extra = createCategory(token, "又一个新的");
        recordTransaction(token, feeId, "500.00", "2026-09-05");

        mockMvc.perform(post("/api/categories/batch-delete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + feeId + "," + extra + "]}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("分类「收取班费」下还有 1 笔流水，先把流水改到别的分类再删"));

        assertEquals(12, getJson(mockMvc, objectMapper, "/api/categories", token).path("data").size(),
                "整批拒绝时两个分类都还在");
    }

    @Test
    @DisplayName("账本批量删除：删掉当前账本会自动切到剩下最早的一本；删到一本不剩直接 400")
    void batchDeleteBooks() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("bat"));
        long first = bookId(token, 0);
        long second = createBook(token, "第二本");
        long third = createBook(token, "第三本");
        assertEquals(3, getJson(mockMvc, objectMapper, "/api/books", token).path("data").size());

        MvcResult result = mockMvc.perform(post("/api/books/batch-delete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + second + "," + third + "]}"))
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode books = json(result, objectMapper).path("data");
        assertEquals(1, books.size());
        assertEquals(first, books.get(0).path("id").asLong());
        assertTrue(books.get(0).path("current").asBoolean(), "删掉当前账本后要自动切到剩下那本");

        mockMvc.perform(post("/api/books/batch-delete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + first + "]}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("至少保留一本账本，先新建一本再删这些（本次选了 1 本）"));

        assertEquals(2, getJson(mockMvc, objectMapper, "/api/books/trash", token).path("data").size(),
                "删掉的两本应进回收站");
    }

    @Test
    @DisplayName("回收站批量彻底删除：删完回收站为空；混进在用账本就整批 404")
    void batchPurgeTrashBooks() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("bat"));
        long keep = bookId(token, 0);
        long trashedA = createBook(token, "要删的 A");
        long trashedB = createBook(token, "要删的 B");

        mockMvc.perform(post("/api/books/batch-delete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + trashedA + "," + trashedB + "]}"))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(2, getJson(mockMvc, objectMapper, "/api/books/trash", token).path("data").size());

        // 混进在用的账本：整批 404，回收站里的两条也不能被删掉
        mockMvc.perform(post("/api/books/batch-purge")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + trashedA + "," + keep + "]}"))
                .andExpect(jsonPath("$.code").value(404));
        assertEquals(2, getJson(mockMvc, objectMapper, "/api/books/trash", token).path("data").size());

        mockMvc.perform(post("/api/books/batch-purge")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + trashedA + "," + trashedB + "]}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(2));
        assertEquals(0, getJson(mockMvc, objectMapper, "/api/books/trash", token).path("data").size());
    }

    @Test
    @DisplayName("分享弹窗：一条链接覆盖选中的多本账本；再次分享换新 token，旧链接失效")
    void batchShareCreatesOneLinkForSelectedBooks() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("bat"));
        long first = bookId(token, 0);
        long second = createBook(token, "第二本");

        MvcResult created = mockMvc.perform(post("/api/books/share")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + first + "," + second + "]}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.books.length()").value(2))
                .andExpect(jsonPath("$.data.books[0].id").value(first))
                .andExpect(jsonPath("$.data.books[1].id").value(second))
                .andReturn();
        String shareToken = json(created, objectMapper).path("data").path("token").asText();
        assertEquals(32, shareToken.length());

        // 一条链接同时能看到两本
        JsonNode books = getJson(mockMvc, objectMapper, "/api/public/report/" + shareToken, token)
                .path("data").path("books");
        assertEquals(2, books.size());

        // 再分享一次：换新 token，旧的立刻 404
        String again = json(mockMvc.perform(post("/api/books/share")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + first + "," + second + "]}"))
                .andReturn(), objectMapper).path("data").path("token").asText();
        assertFalse(again.equals(shareToken), "每次分享都换新 token（泄漏时再分享一次即可作废）");
        assertEquals(404, getJson(mockMvc, objectMapper, "/api/public/report/" + shareToken, token)
                .path("code").asInt());
        assertEquals(200, getJson(mockMvc, objectMapper, "/api/public/report/" + again, token)
                .path("code").asInt());
    }

    @Test
    @DisplayName("归档账本不能批量删流水/分类（后端与单个删除一致地拦）")
    void batchDeleteRejectedWhenArchived() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("bat"));
        long feeId = categoryId(mockMvc, objectMapper, token, "收取班费");
        long tx = recordTransaction(token, feeId, "100.00", "2026-09-01");

        mockMvc.perform(put("/api/books/" + bookId(token, 0) + "/archive")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.archived").value(true));

        mockMvc.perform(post("/api/transactions/batch-delete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + tx + "]}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("当前账本已归档（学期结束），只读；先取消归档再记账或改分类"));

        mockMvc.perform(post("/api/categories/batch-delete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + feeId + "]}"))
                .andExpect(jsonPath("$.code").value(400));
    }

    // ---------- 辅助方法 ----------

    private long bookId(String token, int index) throws Exception {
        return getJson(mockMvc, objectMapper, "/api/books", token).path("data").get(index).path("id").asLong();
    }

    private long createBook(String token, String name) throws Exception {
        return json(mockMvc.perform(post("/api/books")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andReturn(), objectMapper).path("data").path("id").asLong();
    }

    private long createCategory(String token, String name) throws Exception {
        return json(mockMvc.perform(post("/api/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"type\":\"expense\"}"))
                .andReturn(), objectMapper).path("data").path("id").asLong();
    }

    private long recordTransaction(String token, long categoryId, String amount, String date) throws Exception {
        // 只记收入：支出要校验余额，测试里不想为了几条数据去凑余额
        JsonNode data = json(mockMvc.perform(post("/api/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":" + categoryId + ",\"type\":\"income\","
                                + "\"amount\":\"" + amount + "\",\"transDate\":\"" + date + "\"}"))
                .andReturn(), objectMapper).path("data");
        return data.path("id").asLong();
    }
}