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

import static com.bookkeep.web.support.AuthHelper.categoryId;
import static com.bookkeep.web.support.AuthHelper.getJson;
import static com.bookkeep.web.support.AuthHelper.nextUsername;
import static com.bookkeep.web.support.AuthHelper.registerAndLogin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统计接口集成测试（/api/statistics）。
 * <p>口径要点：区间两端可空（都不传 = 全部）、结余是「截止结束月份最后一天」的余额、
 * 金额必须是两位小数的字符串。
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
    @DisplayName("区间汇总：收入/支出只算区间内，结余是区间末（结束月最后一天）的余额")
    void summaryByRangeMatchesHandCalculation() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("stat"));

        record(token, "收取班费", "income", "1900.00", "2026-09-05");
        record(token, "聚餐", "expense", "620.00", "2026-10-12");
        record(token, "活动经费", "expense", "86.50", "2026-11-08");

        // 只看 10 月：支出 620，结余 = 9~10 月底的累计 1280
        JsonNode october = getJson(mockMvc, objectMapper,
                "/api/statistics/summary?startMonth=2026-10&endMonth=2026-10", token).path("data");
        assertEquals("2026-10", october.path("startMonth").asText());
        assertEquals("2026-10", october.path("endMonth").asText());
        assertEquals("0.00", october.path("income").asText());
        assertEquals("620.00", october.path("expense").asText());
        assertEquals("1280.00", october.path("balance").asText());

        // 9~11 月：全部三笔
        JsonNode range = getJson(mockMvc, objectMapper,
                "/api/statistics/summary?startMonth=2026-09&endMonth=2026-11", token).path("data");
        assertEquals("1900.00", range.path("income").asText());
        assertEquals("706.50", range.path("expense").asText());
        assertEquals("1193.50", range.path("balance").asText());

        // 只给结束月份：结余同样截止 10 月底
        JsonNode onlyEnd = getJson(mockMvc, objectMapper,
                "/api/statistics/summary?endMonth=2026-10", token).path("data");
        assertTrue(onlyEnd.path("startMonth").isNull());
        assertEquals("620.00", onlyEnd.path("expense").asText());
        assertEquals("1280.00", onlyEnd.path("balance").asText());
    }

    @Test
    @DisplayName("不传区间 = 全部时间；空账本返回 0.00 而不是 null / \"0\"")
    void summaryDefaultsToAllTime() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("stat"));

        JsonNode empty = getJson(mockMvc, objectMapper, "/api/statistics/summary", token).path("data");
        assertTrue(empty.path("startMonth").isNull());
        assertTrue(empty.path("endMonth").isNull());
        assertEquals("0.00", empty.path("income").asText());
        assertEquals("0.00", empty.path("expense").asText());
        assertEquals("0.00", empty.path("balance").asText());

        record(token, "收取班费", "income", "1900.00", "2026-09-05");
        record(token, "聚餐", "expense", "620.00", "2026-10-12");
        JsonNode all = getJson(mockMvc, objectMapper, "/api/statistics/summary", token).path("data");
        assertEquals("1900.00", all.path("income").asText());
        assertEquals("620.00", all.path("expense").asText());
        assertEquals("1280.00", all.path("balance").asText());
    }

    @Test
    @DisplayName("分类占比：金额降序、percent 两位小数、默认只看支出、按区间聚合")
    void categoryPercentages() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("stat"));

        record(token, "收取班费", "income", "1900.00", "2026-09-05");
        record(token, "聚餐", "expense", "620.00", "2026-11-08");
        record(token, "活动经费", "expense", "86.50", "2026-11-09");
        // 区间外的一笔，不能算进去
        record(token, "聚餐", "expense", "500.00", "2026-08-01");

        JsonNode expenses = getJson(mockMvc, objectMapper,
                "/api/statistics/category?startMonth=2026-11&endMonth=2026-11", token).path("data");
        assertEquals(2, expenses.size());
        // 总额 706.50：620 / 706.50 = 87.76%，86.50 / 706.50 = 12.24%
        assertEquals("聚餐", expenses.get(0).path("categoryName").asText());
        assertEquals("620.00", expenses.get(0).path("amount").asText());
        assertEquals("87.76", expenses.get(0).path("percent").asText());
        assertEquals("活动经费", expenses.get(1).path("categoryName").asText());
        assertEquals("12.24", expenses.get(1).path("percent").asText());

        // 不传区间 = 全部：两笔「聚餐」合并，1120 / 1206.50
        JsonNode all = getJson(mockMvc, objectMapper,
                "/api/statistics/category?type=expense", token).path("data");
        assertEquals(2, all.size());
        assertEquals("1120.00", all.get(0).path("amount").asText());

        JsonNode incomes = getJson(mockMvc, objectMapper,
                "/api/statistics/category?type=income", token).path("data");
        assertEquals(1, incomes.size());
        assertEquals("收取班费", incomes.get(0).path("categoryName").asText());
        assertEquals("100.00", incomes.get(0).path("percent").asText());

        // 没有数据的月份：空数组，不是 400 也不是 null
        JsonNode empty = getJson(mockMvc, objectMapper,
                "/api/statistics/category?startMonth=2026-01&endMonth=2026-01", token).path("data");
        assertEquals(0, empty.size());
    }

    @Test
    @DisplayName("参数校验：月份必须 yyyy-MM、开始不能晚于结束、类型只能是 income/expense")
    void invalidParamsRejected() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("stat"));

        mockMvc.perform(get("/api/statistics/summary?startMonth=2026-8")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())                    // 业务错误：HTTP 200 + body.code
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("月份格式应为 yyyy-MM，例如 2026-08"));

        mockMvc.perform(get("/api/statistics/summary?startMonth=2026-13")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(get("/api/statistics/summary?startMonth=2026-11&endMonth=2026-09")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("开始月份不能晚于结束月份"));

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

        JsonNode summaryB = getJson(mockMvc, objectMapper, "/api/statistics/summary", tokenB).path("data");
        assertEquals("0.00", summaryB.path("income").asText());
        assertEquals("0.00", summaryB.path("balance").asText());

        JsonNode categoriesB = getJson(mockMvc, objectMapper,
                "/api/statistics/category?type=income", tokenB).path("data");
        assertEquals(0, categoriesB.size());

        JsonNode summaryA = getJson(mockMvc, objectMapper, "/api/statistics/summary", tokenA).path("data");
        assertEquals("1000.00", summaryA.path("balance").asText());
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