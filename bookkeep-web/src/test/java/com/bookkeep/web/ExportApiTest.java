package com.bookkeep.web;

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
import static com.bookkeep.web.support.AuthHelper.json;
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
 * 导出接口集成测试（GET /api/transactions/export）。
 * <p>导出是「发给班群」的凭证，因此这里测的是<strong>文件本身</strong>：
 * BOM 在不在、逐行结余对不对、备注里的逗号/引号会不会把表格挤错列、合计与明细是否自洽。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ExportApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("导出 CSV：带 BOM、含表头与逐行结余、合计行自洽")
    void exportContainsBomAndRunningBalance() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("exp"));
        record(token, "收取班费", "income", "1900.00", "2026-09-05", "全班 38 人 × 50");
        record(token, "聚餐", "expense", "620.00", "2026-10-12", "中秋聚餐");
        record(token, "班会物资", "expense", "86.50", "2026-11-08", "横幅,气球");

        MvcResult result = mockMvc.perform(get("/api/transactions/export")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        String contentType = result.getResponse().getContentType();
        assertTrue(contentType.startsWith("text/csv"), "Content-Type 应为 text/csv，实际：" + contentType);
        String disposition = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition.startsWith("attachment"), "应是下载而不是内联渲染：" + disposition);
        assertTrue(disposition.contains("filename*=UTF-8''"), "中文文件名要走 RFC 5987 编码：" + disposition);

        String csv = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        // Excel 靠 BOM 判断 UTF-8，少了就是乱码
        assertTrue(csv.startsWith("\uFEFF"), "文件必须以 UTF-8 BOM 开头");
        assertTrue(csv.contains("日期,分类,摘要,收入,支出,结余"));

        // 逐行结余：1900 → 1280 → 1193.50，且日期升序（结余只有从上往下读才成立）
        assertTrue(csv.contains("2026-09-05,收取班费,全班 38 人 × 50,1900.00,,1900.00"));
        assertTrue(csv.contains("2026-10-12,聚餐,中秋聚餐,,620.00,1280.00"));
        // 备注里有逗号：整格必须加引号，否则这一行会被 Excel 拆成多列
        assertTrue(csv.contains("2026-11-08,班会物资,\"横幅,气球\",,86.50,1193.50"));

        // 抬头汇总与合计行
        assertTrue(csv.contains("账本名,班级账本"));
        assertTrue(csv.contains("期间收入,1900.00"));
        assertTrue(csv.contains("期间支出,706.50"));
        assertTrue(csv.contains("期初结余,0.00"));
        assertTrue(csv.contains("期末结余,1193.50"));
        assertTrue(csv.contains("合计,,,1900.00,706.50,1193.50"));

        // 分类合计：支出在前（金额降序），收入在后
        assertTrue(csv.contains("分类合计"));
        assertTrue(csv.contains("聚餐,支出,620.00"));
        assertTrue(csv.contains("收取班费,收入,1900.00"));
    }

    @Test
    @DisplayName("备注里的双引号按 CSV 规则转义成两个引号")
    void quotesAreEscaped() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("exp"));
        record(token, "收取班费", "income", "100.00", "2026-09-05", null);
        record(token, "聚餐", "expense", "10.00", "2026-09-06", "他说\"好\"");

        String csv = export(token, "");
        assertTrue(csv.contains("\"他说\"\"好\"\"\""), "引号应双写并整体加引号，实际内容：\n" + csv);
    }

    @Test
    @DisplayName("按日期区间导出：期初结余取区间之前，明细只含区间内")
    void exportDateRangeShowsOpeningBalance() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("exp"));
        record(token, "收取班费", "income", "1900.00", "2026-09-05", "全班 38 人 × 50");
        record(token, "聚餐", "expense", "620.00", "2026-10-12", "中秋聚餐");
        record(token, "班会物资", "expense", "86.50", "2026-11-08", "横幅");

        String csv = export(token, "?startDate=2026-10-01&endDate=2026-10-31");
        assertTrue(csv.contains("统计区间,2026-10-01 至 2026-10-31"));
        assertTrue(csv.contains("期初结余,1900.00"));   // 9 月那笔算期初
        assertTrue(csv.contains("期间收入,0.00"));
        assertTrue(csv.contains("期间支出,620.00"));
        assertTrue(csv.contains("期末结余,1280.00"));
        // 区间内的明细只有 10 月这一笔；9 月与 11 月不能出现
        assertTrue(csv.contains("2026-10-12,聚餐,中秋聚餐,,620.00,1280.00"));
        assertFalse(csv.contains("2026-09-05"), "区间外的流水不应出现在明细里");
        assertFalse(csv.contains("2026-11-08"));
    }

    @Test
    @DisplayName("空账本也能导出：BOM + 表头 + 合计 0.00")
    void emptyExportIsStillWellFormed() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("exp"));

        String csv = export(token, "");
        assertTrue(csv.startsWith("\uFEFF"));
        assertTrue(csv.contains("日期,分类,摘要,收入,支出,结余"));
        assertTrue(csv.contains("合计,,,0.00,0.00,0.00"));
        assertTrue(csv.contains("期末结余,0.00"));
    }

    @Test
    @DisplayName("开始日期晚于结束日期：业务错误（HTTP 200 + code=400）")
    void reversedRangeRejected() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("exp"));

        mockMvc.perform(get("/api/transactions/export?startDate=2026-12-01&endDate=2026-11-01")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("开始日期不能晚于结束日期"));
    }

    @Test
    @DisplayName("越权隔离：B 导出的文件里没有 A 的流水")
    void exportIsIsolatedBetweenUsers() throws Exception {
        String tokenA = registerAndLogin(mockMvc, objectMapper, nextUsername("exp"));
        String tokenB = registerAndLogin(mockMvc, objectMapper, nextUsername("exp"));
        record(tokenA, "收取班费", "income", "1000.00", "2026-11-01", "A 的班费");

        String csvB = export(tokenB, "");
        assertFalse(csvB.contains("A 的班费"));
        assertTrue(csvB.contains("合计,,,0.00,0.00,0.00"));
    }

    @Test
    @DisplayName("未登录导出：真实 401 + JSON 信封")
    void exportRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/transactions/export"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    // ---------- 辅助方法 ----------

    private String export(String token, String query) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/transactions/export" + query)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void record(String token, String categoryName, String type, String amount,
                        String transDate, String note) throws Exception {
        long categoryId = categoryId(mockMvc, objectMapper, token, categoryName);
        String body = "{\"categoryId\":" + categoryId + ",\"type\":\"" + type + "\","
                + "\"amount\":\"" + amount + "\",\"transDate\":\"" + transDate + "\","
                + "\"note\":" + (note == null ? "null" : objectMapper.writeValueAsString(note)) + "}";
        MvcResult result = mockMvc.perform(post("/api/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(200, json(result, objectMapper).path("code").asInt(),
                "记一笔失败：" + result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}