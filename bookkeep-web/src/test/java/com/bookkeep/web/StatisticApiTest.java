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
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.time.ZoneId;

import static com.bookkeep.web.support.AuthHelper.categoryId;
import static com.bookkeep.web.support.AuthHelper.getJson;
import static com.bookkeep.web.support.AuthHelper.nextUsername;
import static com.bookkeep.web.support.AuthHelper.registerAndLogin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统计接口集成测试（/api/statistics）。
 * <p>核心是「口径」：数字要能手算对上、金额必须是两位小数的字符串、本月结余与累计余额不能混。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StatisticApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("月度汇总：income/expense/balance 只算本月，totalBalance 是累计余额")
    void summaryByMonthMatchesHandCalculation() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("stat"));

        record(token, "收取班费", "income", "1900.00", "2026-09-05");
        record(token, "聚餐", "expense", "620.00", "2026-10-12");
        record(token, "活动经费", "expense", "86.50", "2026-11-08");

        // 10 月：只有一笔支出，本月结余为负；累计余额要把 9 月和 11 月也算进去
        JsonNode october = getJson(mockMvc, objectMapper,
                "/api/statistics/summary?month=2026-10", token).path("data");
        assertEquals("2026-10", october.path("month").asText());
        assertEquals("0.00", october.path("income").asText());
        assertEquals("620.00", october.path("expense").asText());
        assertEquals("-620.00", october.path("balance").asText());
        assertEquals("1193.50", october.path("totalBalance").asText());

        JsonNode september = getJson(mockMvc, objectMapper,
                "/api/statistics/summary?month=2026-09", token).path("data");
        assertEquals("1900.00", september.path("income").asText());
        assertEquals("0.00", september.path("expense").asText());
        assertEquals("1900.00", september.path("balance").asText());
        assertEquals("1193.50", september.path("totalBalance").asText());
    }

    @Test
    @DisplayName("不传 month 按当月统计；空账本返回 0.00 而不是 null / \"0\"")
    void summaryDefaultsToCurrentMonth() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("stat"));
        String currentMonth = YearMonth.now(ZoneId.of("Asia/Shanghai")).toString();

        JsonNode data = getJson(mockMvc, objectMapper, "/api/statistics/summary", token).path("data");
        assertEquals(currentMonth, data.path("month").asText());
        assertEquals("0.00", data.path("income").asText());
        assertEquals("0.00", data.path("expense").asText());
        assertEquals("0.00", data.path("balance").asText());
        assertEquals("0.00", data.path("totalBalance").asText());
    }

    @Test
    @DisplayName("分类占比：金额降序、percent 两位小数、默认只看支出")
    void categoryPercentages() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("stat"));

        record(token, "收取班费", "income", "1900.00", "2026-09-05");
        record(token, "聚餐", "expense", "620.00", "2026-11-08");
        record(token, "活动经费", "expense", "86.50", "2026-11-09");

        JsonNode expenses = getJson(mockMvc, objectMapper,
                "/api/statistics/category?month=2026-11", token).path("data");
        assertEquals(2, expenses.size());
        // 总额 706.50：620 / 706.50 = 87.76%，86.50 / 706.50 = 12.24%
        assertEquals("聚餐", expenses.get(0).path("categoryName").asText());
        assertEquals("620.00", expenses.get(0).path("amount").asText());
        assertEquals("87.76", expenses.get(0).path("percent").asText());
        assertEquals("活动经费", expenses.get(1).path("categoryName").asText());
        assertEquals("12.24", expenses.get(1).path("percent").asText());

        JsonNode incomes = getJson(mockMvc, objectMapper,
                "/api/statistics/category?month=2026-09&type=income", token).path("data");
        assertEquals(1, incomes.size());
        assertEquals("收取班费", incomes.get(0).path("categoryName").asText());
        assertEquals("100.00", incomes.get(0).path("percent").asText());

        // 没有数据的月份：空数组，不是 400 也不是 null
        JsonNode empty = getJson(mockMvc, objectMapper,
                "/api/statistics/category?month=2026-01", token).path("data");
        assertEquals(0, empty.size());
    }

    @Test
    @DisplayName("参数校验：月份格式必须是 yyyy-MM，类型只能是 income/expense")
    void invalidParamsRejected() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("stat"));

        mockMvc.perform(get("/api/statistics/summary?month=2026-8")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())                    // 业务错误：HTTP 200 + body.code
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("月份格式应为 yyyy-MM，例如 2026-08"));

        mockMvc.perform(get("/api/statistics/summary?month=2026-13")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(get("/api/statistics/category?type=all")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("收支类型只能是 income 或 expense"));
    }

    @Test
    @DisplayName("越权隔离：A 的流水不出现在 B 的统计里")
    void statisticsAreIsolatedBetweenUsers() throws Exception {
        String tokenA = registerAndLogin(mockMvc, objectMapper, nextUsername("stat"));
        String tokenB = registerAndLogin(mockMvc, objectMapper, nextUsername("stat"));
        record(tokenA, "收取班费", "income", "1000.00", "2026-11-01");

        JsonNode summaryB = getJson(mockMvc, objectMapper,
                "/api/statistics/summary?month=2026-11", tokenB).path("data");
        assertEquals("0.00", summaryB.path("income").asText());
        assertEquals("0.00", summaryB.path("totalBalance").asText());

        JsonNode categoriesB = getJson(mockMvc, objectMapper,
                "/api/statistics/category?month=2026-11&type=income", tokenB).path("data");
        assertEquals(0, categoriesB.size());

        // A 自己看到的仍然是 1000
        JsonNode summaryA = getJson(mockMvc, objectMapper,
                "/api/statistics/summary?month=2026-11", tokenA).path("data");
        assertEquals("1000.00", summaryA.path("totalBalance").asText());
    }

    @Test
    @DisplayName("未登录访问统计：真实 401 + JSON 信封")
    void statisticsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/statistics/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    // ---------- 辅助方法 ----------

    private void record(String token, String categoryName, String type, String amount, String transDate)
            throws Exception {
        long categoryId = categoryId(mockMvc, objectMapper, token, categoryName);
        mockMvc.perform(post("/api/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":" + categoryId + ",\"type\":\"" + type + "\","
                                + "\"amount\":\"" + amount + "\",\"transDate\":\"" + transDate + "\"}"))
                .andExpect(jsonPath("$.code").value(200));
    }
}