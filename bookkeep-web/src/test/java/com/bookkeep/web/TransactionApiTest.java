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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import static com.bookkeep.web.support.AuthHelper.categoryId;
import static com.bookkeep.web.support.AuthHelper.getJson;
import static com.bookkeep.web.support.AuthHelper.json;
import static com.bookkeep.web.support.AuthHelper.nextUsername;
import static com.bookkeep.web.support.AuthHelper.registerAndLogin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 流水接口集成测试（/api/transactions）。
 * <p>覆盖四类风险：金额精度与序列化（字符串）、余额校验（含修改时排除自身）、
 * 分页筛选是否真的分页、越权隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TransactionApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("记一笔收入 + 一笔支出：列表按日期倒序，金额是字符串（不是浮点数）")
    void createIncomeAndExpense() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("txn"));
        long feeId = categoryId(mockMvc, objectMapper, token, "收取班费");
        long diningId = categoryId(mockMvc, objectMapper, token, "聚餐");

        createTransaction(token, feeId, "income", "1900.00", "2026-09-05", "全班 38 人 × 50")
                .andExpect(jsonPath("$.code").value(200))
                // 金额必须是字符串：BigDecimal 被 JacksonConfig 按字符串序列化
                .andExpect(jsonPath("$.data.amount").value("1900.00"))
                .andExpect(jsonPath("$.data.categoryName").value("收取班费"));

        createTransaction(token, diningId, "expense", "620.00", "2026-11-08", "中秋聚餐补差")
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.categoryName").value("聚餐"))
                .andExpect(jsonPath("$.data.categoryIcon").value("dining"));

        JsonNode page = getJson(mockMvc, objectMapper, "/api/transactions", token).path("data");
        assertEquals(2, page.path("total").asLong());
        JsonNode list = page.path("list");
        assertEquals(2, list.size());
        assertEquals("2026-11-08", list.get(0).path("transDate").asText()); // 日期倒序
        assertEquals("聚餐", list.get(0).path("categoryName").asText());
        assertEquals("620.00", list.get(0).path("amount").asText());

        // 余额 = 1900 - 620
        assertEquals("1280.00", getJson(mockMvc, objectMapper, "/api/transactions/balance", token)
                .path("data").asText());
    }

    @Test
    @DisplayName("支出超过余额：400 且提示当前余额；余额内正常")
    void expenseOverBalanceRejected() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("txn"));
        long feeId = categoryId(mockMvc, objectMapper, token, "收取班费");
        long activityId = categoryId(mockMvc, objectMapper, token, "活动经费");

        createTransaction(token, feeId, "income", "100.00", "2026-11-01", null)
                .andExpect(jsonPath("$.code").value(200));

        createTransaction(token, activityId, "expense", "150.00", "2026-11-02", null)
                .andExpect(status().isOk())                        // 业务错误仍是 HTTP 200
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("班费余额不足，当前余额 100.00"));

        createTransaction(token, activityId, "expense", "100.00", "2026-11-02", null)
                .andExpect(jsonPath("$.code").value(200));         // 刚好花完可以

        assertEquals("0.00", getJson(mockMvc, objectMapper, "/api/transactions/balance", token)
                .path("data").asText());
    }

    @Test
    @DisplayName("修改流水：按「排除自身的余额」判断，改小可以、改超不行")
    void updateRecalculatesBalance() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("txn"));
        long feeId = categoryId(mockMvc, objectMapper, token, "收取班费");
        long activityId = categoryId(mockMvc, objectMapper, token, "活动经费");

        createTransaction(token, feeId, "income", "100.00", "2026-11-01", null);
        MvcResult created = createTransaction(token, activityId, "expense", "80.00", "2026-11-02", null)
                .andReturn();
        long txId = json(created, objectMapper).path("data").path("id").asLong();

        // 排除自己后余额是 100 → 改成 120 超支
        mockMvc.perform(put("/api/transactions/" + txId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(activityId, "expense", "120.00", "2026-11-02", null)))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("班费余额不足，当前余额 100.00"));

        // 改成 50 没问题；余额 = 100 - 50
        mockMvc.perform(put("/api/transactions/" + txId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(activityId, "expense", "50.00", "2026-11-02", "改小了")))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.amount").value("50.00"))
                .andExpect(jsonPath("$.data.note").value("改小了"));

        assertEquals("50.00", getJson(mockMvc, objectMapper, "/api/transactions/balance", token)
                .path("data").asText());
    }

    @Test
    @DisplayName("分页真的分页（不是全表返回）+ 按类型/日期/关键字筛选")
    void paginationAndFilters() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("txn"));
        long feeId = categoryId(mockMvc, objectMapper, token, "收取班费");
        long diningId = categoryId(mockMvc, objectMapper, token, "聚餐");
        long activityId = categoryId(mockMvc, objectMapper, token, "活动经费");

        createTransaction(token, feeId, "income", "1900.00", "2026-09-05", "收取班费");
        createTransaction(token, diningId, "expense", "620.00", "2026-10-12", "中秋聚餐");
        createTransaction(token, activityId, "expense", "86.50", "2026-11-08", "班会物资");

        JsonNode page1 = getJson(mockMvc, objectMapper, "/api/transactions?page=1&size=2", token).path("data");
        assertEquals(3, page1.path("total").asLong());
        assertEquals(2, page1.path("list").size());          // 关键：不是全表 3 条

        JsonNode page2 = getJson(mockMvc, objectMapper, "/api/transactions?page=2&size=2", token).path("data");
        assertEquals(1, page2.path("list").size());
        // 第 2 页不能和第 1 页重复
        long firstIdOnPage1 = page1.path("list").get(0).path("id").asLong();
        long firstIdOnPage2 = page2.path("list").get(0).path("id").asLong();
        assertTrue(firstIdOnPage1 != firstIdOnPage2);

        JsonNode expenses = getJson(mockMvc, objectMapper,
                "/api/transactions?type=expense", token).path("data");
        assertEquals(2, expenses.path("total").asLong());

        JsonNode october = getJson(mockMvc, objectMapper,
                "/api/transactions?startDate=2026-10-01&endDate=2026-10-31", token).path("data");
        assertEquals(1, october.path("total").asLong());
        assertEquals("聚餐", october.path("list").get(0).path("categoryName").asText());

        JsonNode byKeyword = getJson(mockMvc, objectMapper,
                "/api/transactions?keyword=聚餐", token).path("data");
        assertEquals(1, byKeyword.path("total").asLong());

        JsonNode byCategory = getJson(mockMvc, objectMapper,
                "/api/transactions?categoryId=" + activityId, token).path("data");
        assertEquals(1, byCategory.path("total").asLong());
    }

    @Test
    @DisplayName("删除流水后余额恢复，列表里也查不到（逻辑删除）")
    void deleteRestoresBalance() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("txn"));
        long feeId = categoryId(mockMvc, objectMapper, token, "收取班费");
        long activityId = categoryId(mockMvc, objectMapper, token, "活动经费");

        createTransaction(token, feeId, "income", "100.00", "2026-11-01", null);
        MvcResult created = createTransaction(token, activityId, "expense", "40.00", "2026-11-02", null)
                .andReturn();
        long txId = json(created, objectMapper).path("data").path("id").asLong();

        assertEquals("60.00", getJson(mockMvc, objectMapper, "/api/transactions/balance", token)
                .path("data").asText());

        mockMvc.perform(delete("/api/transactions/" + txId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(200));

        assertEquals("100.00", getJson(mockMvc, objectMapper, "/api/transactions/balance", token)
                .path("data").asText());

        JsonNode page = getJson(mockMvc, objectMapper, "/api/transactions", token).path("data");
        assertEquals(1, page.path("total").asLong());

        // 再删一次：查不到 → 404
        mockMvc.perform(delete("/api/transactions/" + txId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("流水类型必须与分类一致（前端切换分类残留旧 type 时挡下来）")
    void typeMustMatchCategory() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("txn"));
        long diningId = categoryId(mockMvc, objectMapper, token, "聚餐"); // expense 分类

        createTransaction(token, diningId, "income", "10.00", "2026-11-01", null)
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("流水类型与所选分类不一致"));
    }

    @Test
    @DisplayName("越权隔离：A 的流水 B 看不到、改不了；B 也不能用 A 的分类记账")
    void transactionsAreIsolatedBetweenUsers() throws Exception {
        String tokenA = registerAndLogin(mockMvc, objectMapper, nextUsername("txn"));
        String tokenB = registerAndLogin(mockMvc, objectMapper, nextUsername("txn"));
        long feeIdA = categoryId(mockMvc, objectMapper, tokenA, "收取班费");

        MvcResult created = createTransaction(tokenA, feeIdA, "income", "500.00", "2026-11-01", "A 的班费")
                .andReturn();
        long txIdA = json(created, objectMapper).path("data").path("id").asLong();

        JsonNode pageB = getJson(mockMvc, objectMapper, "/api/transactions", tokenB).path("data");
        assertEquals(0, pageB.path("total").asLong());
        assertFalse(pageB.toString().contains("A 的班费"));

        mockMvc.perform(put("/api/transactions/" + txIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(feeIdA, "income", "1.00", "2026-11-01", "偷改")))
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(delete("/api/transactions/" + txIdA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(jsonPath("$.code").value(404));

        // B 用 A 的分类 id 记账：分类不属于 B 的账本 → 404
        createTransaction(tokenB, feeIdA, "income", "100.00", "2026-11-02", null)
                .andExpect(jsonPath("$.code").value(404));

        // A 的数据没被动过
        assertEquals("500.00", getJson(mockMvc, objectMapper, "/api/transactions/balance", tokenA)
                .path("data").asText());
    }

    @Test
    @DisplayName("参数校验：分页越界 / 金额为 0 / 缺日期 → 400")
    void invalidParamsRejected() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("txn"));
        long feeId = categoryId(mockMvc, objectMapper, token, "收取班费");

        mockMvc.perform(get("/api/transactions?page=0").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(get("/api/transactions?size=101").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(400));

        createTransaction(token, feeId, "income", "0.00", "2026-11-01", null)
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/api/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":" + feeId + ",\"type\":\"income\",\"amount\":\"10.00\"}"))
                .andExpect(jsonPath("$.code").value(400));   // 缺 transDate
    }

    // ---------- 辅助方法 ----------

    private ResultActions createTransaction(String token, long categoryId, String type, String amount,
                                            String transDate, String note) throws Exception {
        return mockMvc.perform(post("/api/transactions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(categoryId, type, amount, transDate, note)));
    }

    private String body(long categoryId, String type, String amount, String transDate, String note) {
        return "{\"categoryId\":" + categoryId + ",\"type\":\"" + type + "\",\"amount\":\"" + amount + "\","
                + "\"transDate\":\"" + transDate + "\",\"note\":" + (note == null ? "null" : "\"" + note + "\"") + "}";
    }
}