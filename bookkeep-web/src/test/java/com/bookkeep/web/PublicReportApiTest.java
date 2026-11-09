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
    @DisplayName("免登录可读：账本名 + 本月收支 + 分类占比 + 逐行结余，且不含个人信息")
    void publicReportReadableWithoutLogin() throws Exception {
        String username = nextUsername("pub");
        String token = registerAndLogin(mockMvc, objectMapper, username);
        String shareToken = shareToken(mockMvc, objectMapper, username);
        assertEquals(32, shareToken.length(), "公示 token 是 32 位 UUID 去横线");

        record(token, "收取班费", "income", "1900.00", "2026-09-05", "全班 38 人 × 50");
        record(token, "班会物资", "expense", "86.50", "2026-09-20", "横幅+气球");

        // 关键：不带 Authorization，公共接口必须放行
        MvcResult result = mockMvc.perform(get("/api/public/report/" + shareToken + "?month=2026-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        assertEquals("班级账本", data.path("bookName").asText());
        assertEquals("2026-09", data.path("month").asText());
        assertEquals("1900.00", data.path("income").asText());
        assertEquals("86.50", data.path("expense").asText());
        assertEquals("1813.50", data.path("balance").asText());
        assertEquals("1813.50", data.path("totalBalance").asText());

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
    @DisplayName("跨月流水：只出本月明细，但结余仍是全账本累计（不从 0 重算）")
    void runningBalanceSpansMonths() throws Exception {
        String username = nextUsername("pub");
        String token = registerAndLogin(mockMvc, objectMapper, username);
        record(token, "收取班费", "income", "1900.00", "2026-09-05", null);
        record(token, "聚餐", "expense", "620.00", "2026-10-12", null);

        JsonNode data = getJson(mockMvc, objectMapper,
                "/api/public/report/" + shareToken(mockMvc, objectMapper, username) + "?month=2026-10", token)
                .path("data");

        assertEquals("0.00", data.path("income").asText());
        assertEquals("620.00", data.path("expense").asText());
        assertEquals("-620.00", data.path("balance").asText());
        assertEquals("1280.00", data.path("totalBalance").asText());
        assertEquals(1, data.path("transactions").size());
        // 若窗口函数先按月过滤再累计，这里会是 -620.00
        assertEquals("1280.00", data.path("transactions").get(0).path("balanceAfter").asText());
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
                "/api/public/report/" + shareToken(mockMvc, objectMapper, userB), tokenA).path("data");

        assertEquals("0.00", reportB.path("totalBalance").asText());
        assertEquals(0, reportB.path("transactions").size());
        assertFalse(reportB.toString().contains("A 的班费"));
    }

    @Test
    @DisplayName("公示页不传 month 时按当月，月份格式错误返回 400")
    void monthDefaultsAndValidation() throws Exception {
        String username = nextUsername("pub");
        registerAndLogin(mockMvc, objectMapper, username);
        String token = shareToken(mockMvc, objectMapper, username);

        mockMvc.perform(get("/api/public/report/" + token))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.month").isNotEmpty())
                .andExpect(jsonPath("$.data.income").value("0.00"));

        mockMvc.perform(get("/api/public/report/" + token + "?month=2026-9"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("月份格式应为 yyyy-MM，例如 2026-08"));
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