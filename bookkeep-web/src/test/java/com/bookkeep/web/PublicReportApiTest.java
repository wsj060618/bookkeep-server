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
import static com.bookkeep.web.support.AuthHelper.nextUsername;
import static com.bookkeep.web.support.AuthHelper.registerAndLogin;
import static com.bookkeep.web.support.AuthHelper.shareToken;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公示页接口集成测试（GET /api/public/report/{shareToken}）。
 * <p>这个接口是唯一免登录的数据出口，测试重点不是「能查到」而是三件事：
 * 不带 token 也能看（否则同学还是要账号）、看不到别人的账（token 就是唯一凭证）、
 * 不泄露任何个人信息（username / email 一律不出现在响应里）。
 * <p>口径与统计页一致：区间两端可空（都不传 = 全部时间），balance 是区间末账户余额。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PublicReportApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("免登录可读：不传区间 = 全部时间，含分类占比与逐行结余，且不含个人信息")
    void publicReportReadableWithoutLogin() throws Exception {
        String username = nextUsername("pub");
        String token = registerAndLogin(mockMvc, objectMapper, username);
        String shareToken = shareToken(mockMvc, objectMapper, username);
        assertEquals(32, shareToken.length(), "公示 token 是 32 位 UUID 去横线");

        record(token, "收取班费", "income", "1900.00", "2026-09-05", "全班 38 人 × 50");
        record(token, "班会物资", "expense", "86.50", "2026-09-20", "横幅+气球");

        // 关键：不带 Authorization，公共接口必须放行
        MvcResult result = mockMvc.perform(get("/api/public/report/" + shareToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        // 一条链接可以带多本账本：数据在 data.books[0] 里
        JsonNode data = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data").path("books").get(0);
        assertEquals("班级账本", data.path("bookName").asText());
        assertTrue(data.path("startDate").isNull(), "不传区间时 startDate 为 null");
        assertTrue(data.path("endDate").isNull(), "不传区间时 endDate 为 null");
        assertEquals("1900.00", data.path("income").asText());
        assertEquals("86.50", data.path("expense").asText());
        // 期末结余 = 全账本余额（全部时间时就是当前余额），不是收支差
        assertEquals("1813.50", data.path("balance").asText());

        JsonNode expenseCategories = data.path("expenseCategories");
        assertEquals(1, expenseCategories.size());
        assertEquals("班会物资", expenseCategories.get(0).path("categoryName").asText());
        assertEquals("86.50", expenseCategories.get(0).path("amount").asText());
        assertEquals("100.00", expenseCategories.get(0).path("percent").asText());

        // 收入分类占比也要出现在公示页（钱从哪来），口径与统计接口同一份
        JsonNode incomeCategories = data.path("incomeCategories");
        assertEquals(1, incomeCategories.size());
        assertEquals("收取班费", incomeCategories.get(0).path("categoryName").asText());
        assertEquals("1900.00", incomeCategories.get(0).path("amount").asText());
        assertEquals("100.00", incomeCategories.get(0).path("percent").asText());

        JsonNode transactions = data.path("transactions");
        assertEquals(2, transactions.size());
        // 日期升序 + 逐行结余
        assertEquals("2026-09-05", transactions.get(0).path("transDate").asText());
        assertEquals("1900.00", transactions.get(0).path("balanceAfter").asText());
        assertEquals("2026-09-20", transactions.get(1).path("transDate").asText());
        assertEquals("1813.50", transactions.get(1).path("balanceAfter").asText());

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains(username), "公示页不能出现用户名");
        assertFalse(body.contains("\"email\""), "公示页不能出现邮箱字段");
    }

    @Test
    @DisplayName("区间筛选精确到天：只出区间内明细，结余是区间末余额；逐行结余仍按全账本累计")
    void rangeFiltersTransactionsAndTakesEndBalance() throws Exception {
        String username = nextUsername("pub");
        String token = registerAndLogin(mockMvc, objectMapper, username);
        record(token, "收取班费", "income", "1900.00", "2026-09-05", null);
        record(token, "聚餐", "expense", "620.00", "2026-10-12", null);
        record(token, "活动经费", "expense", "86.50", "2026-11-08", null);

        JsonNode onlyOctober = getJson(mockMvc, objectMapper,
                "/api/public/report/" + shareToken(mockMvc, objectMapper, username)
                        + "?startDate=2026-10-01&endDate=2026-10-31", token)
                .path("data").path("books").get(0);

        assertEquals("2026-10-01", onlyOctober.path("startDate").asText());
        assertEquals("2026-10-31", onlyOctober.path("endDate").asText());
        assertEquals("0.00", onlyOctober.path("income").asText());
        assertEquals("620.00", onlyOctober.path("expense").asText());
        // 区间末（10-31）的余额：1900 − 620
        assertEquals("1280.00", onlyOctober.path("balance").asText());
        assertEquals(1, onlyOctober.path("transactions").size());
        // 若窗口函数先按区间过滤再累计，这里会是 -620.00
        assertEquals("1280.00", onlyOctober.path("transactions").get(0).path("balanceAfter").asText());

        // 天数粒度：10-13 起就看不到 10-12 那笔（按月筛的话它会跟着 10 月一起出来）
        JsonNode afterTwelfth = getJson(mockMvc, objectMapper,
                "/api/public/report/" + shareToken(mockMvc, objectMapper, username)
                        + "?startDate=2026-10-13&endDate=2026-10-31", token)
                .path("data").path("books").get(0);
        assertEquals(0, afterTwelfth.path("transactions").size(), "10-12 不该出现在 10-13 起的区间里");
        assertEquals("0.00", afterTwelfth.path("expense").asText());

        // 单日区间也成立
        JsonNode oneDay = getJson(mockMvc, objectMapper,
                "/api/public/report/" + shareToken(mockMvc, objectMapper, username)
                        + "?startDate=2026-10-12&endDate=2026-10-12", token)
                .path("data").path("books").get(0);
        assertEquals(1, oneDay.path("transactions").size());
        assertEquals("620.00", oneDay.path("transactions").get(0).path("amount").asText());

        // 只给结束日期：从最早算到 10-31
        JsonNode onlyEnd = getJson(mockMvc, objectMapper,
                "/api/public/report/" + shareToken(mockMvc, objectMapper, username) + "?endDate=2026-10-31", token)
                .path("data").path("books").get(0);
        assertTrue(onlyEnd.path("startDate").isNull());
        assertEquals("1900.00", onlyEnd.path("income").asText());
        assertEquals("620.00", onlyEnd.path("expense").asText());
        assertEquals(2, onlyEnd.path("transactions").size());

        // 结束日期不限：结余取当前余额（三笔都算）
        JsonNode openEnd = getJson(mockMvc, objectMapper,
                "/api/public/report/" + shareToken(mockMvc, objectMapper, username) + "?startDate=2026-10-01", token)
                .path("data").path("books").get(0);
        assertEquals("1193.50", openEnd.path("balance").asText());
        assertEquals(2, openEnd.path("transactions").size());
    }

    @Test
    @DisplayName("未知 token：404「公示页不存在或已失效」")
    void unknownTokenReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/public/report/0123456789abcdef0123456789abcdef"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("公示页不存在或已失效"));
    }

    @Test
    @DisplayName("token 就是边界：A 的 token 看不到 B 的账")
    void tokensDoNotLeakOtherBooks() throws Exception {
        String userA = nextUsername("pub");
        String tokenA = registerAndLogin(mockMvc, objectMapper, userA);
        record(tokenA, "收取班费", "income", "1000.00", "2026-11-01", "A 的班费");

        String userB = nextUsername("pub");
        registerAndLogin(mockMvc, objectMapper, userB);
        JsonNode reportB = getJson(mockMvc, objectMapper,
                "/api/public/report/" + shareToken(mockMvc, objectMapper, userB), tokenA)
                .path("data").path("books").get(0);

        assertEquals("0.00", reportB.path("balance").asText());
        assertEquals(0, reportB.path("transactions").size());
        assertFalse(reportB.toString().contains("A 的班费"));
    }

    @Test
    @DisplayName("区间参数：格式错 / 开始晚于结束都返回 400")
    void rangeValidation() throws Exception {
        String username = nextUsername("pub");
        registerAndLogin(mockMvc, objectMapper, username);
        String token = shareToken(mockMvc, objectMapper, username);

        mockMvc.perform(get("/api/public/report/" + token))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.books[0].income").value("0.00"))
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty());

        mockMvc.perform(get("/api/public/report/" + token + "?startDate=2026-9-1"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("日期格式应为 yyyy-MM-dd，例如 2026-08-01"));

        mockMvc.perform(get("/api/public/report/" + token + "?startDate=2026-11-01&endDate=2026-09-30"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("开始日期不能晚于结束日期"));
    }

    @Test
    @DisplayName("放行的只有公示页：业务接口仍然必须登录")
    void otherPathsStillRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/transactions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        mockMvc.perform(get("/api/statistics/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    // ---------- 辅助方法 ----------

    private void record(String token, String categoryName, String type, String amount,
                        String transDate, String note) throws Exception {
        long categoryId = categoryId(mockMvc, objectMapper, token, categoryName);
        String body = "{\"categoryId\":" + categoryId + ",\"type\":\"" + type + "\","
                + "\"amount\":\"" + amount + "\",\"transDate\":\"" + transDate + "\","
                + "\"note\":" + (note == null ? "null" : objectMapper.writeValueAsString(note)) + "}";
        mockMvc.perform(post("/api/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(jsonPath("$.code").value(200));
    }
}
