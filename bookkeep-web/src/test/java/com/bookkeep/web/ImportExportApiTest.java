package com.bookkeep.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static com.bookkeep.web.support.AuthHelper.getJson;
import static com.bookkeep.web.support.AuthHelper.json;
import static com.bookkeep.web.support.AuthHelper.nextUsername;
import static com.bookkeep.web.support.AuthHelper.registerAndLogin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 导入导出（PLAN §10.3）：
 * <ul>
 *   <li>xlsx 导出：真的是 Excel（POI 能打开）、有汇总与两张分类图的数据源</li>
 *   <li>CSV 导入：行级失败不阻塞其它行、分类不存在不自动建、支出受余额约束、表头/BOM 容错</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ImportExportApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("xlsx 导出：版式对齐班费表（顶部收入/支出/结余、两行表头、明细从第 10 行开始）")
    void exportXlsxIsRealWorkbook() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("xls"));
        long feeId = categoryIdOf(token, "收取班费");
        long diningId = categoryIdOf(token, "聚餐");
        record(token, feeId, "income", "1900.00", "2026-09-05", "全班 38 人");
        record(token, diningId, "expense", "620.00", "2026-09-20", "中秋聚餐");

        MvcResult async = mockMvc.perform(get("/api/transactions/export?format=xlsx")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult result = mockMvc.perform(asyncDispatch(async))
                .andExpect(status().isOk())
                .andReturn();

        String contentType = result.getResponse().getContentType();
        assertTrue(contentType.contains("spreadsheetml"), "Content-Type 应是 xlsx，实际：" + contentType);
        String disposition = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition != null && disposition.contains(".xlsx"), "文件名应以 .xlsx 结尾：" + disposition);

        byte[] bytes = result.getResponse().getContentAsByteArray();
        assertTrue(bytes.length > 0);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertEquals(2, workbook.getNumberOfSheets());
            var sheet = workbook.getSheet("收支明细");
            assertNotNull(sheet, "应有「收支明细」sheet");
            assertEquals("班级账本 班费收支明细", sheet.getRow(0).getCell(1).getStringCellValue());
            // 顶部三行汇总：收入 / 支出 / 结余
            assertEquals("收入", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals(1900.00, sheet.getRow(1).getCell(3).getNumericCellValue(), 0.001);
            assertEquals("支出", sheet.getRow(3).getCell(1).getStringCellValue());
            assertEquals(620.00, sheet.getRow(3).getCell(3).getNumericCellValue(), 0.001);
            assertEquals("结余", sheet.getRow(5).getCell(1).getStringCellValue());
            assertEquals(1280.00, sheet.getRow(5).getCell(3).getNumericCellValue(), 0.001);
            // 两行表头：收入 / 支出 分组
            assertEquals("序号", sheet.getRow(7).getCell(1).getStringCellValue());
            assertEquals("收入", sheet.getRow(7).getCell(4).getStringCellValue());
            assertEquals("支出", sheet.getRow(7).getCell(7).getStringCellValue());
            assertEquals("总价", sheet.getRow(8).getCell(6).getStringCellValue());
            assertEquals("金额", sheet.getRow(8).getCell(9).getStringCellValue());
            // 明细从第 10 行（0 基 9）开始：序号 1 是那笔收入
            assertEquals(1, (int) sheet.getRow(9).getCell(1).getNumericCellValue());
            assertEquals("收取班费", sheet.getRow(9).getCell(3).getStringCellValue());
            assertEquals(1900.00, sheet.getRow(9).getCell(6).getNumericCellValue(), 0.001);
            assertEquals(1900.00, sheet.getRow(9).getCell(10).getNumericCellValue(), 0.001);
            assertEquals(620.00, sheet.getRow(10).getCell(9).getNumericCellValue(), 0.001);

            assertNotNull(workbook.getSheet("分类合计"), "图表数据源 sheet 应在");
        }
    }

    @Test
    @DisplayName("CSV 导入：成功行入库，失败行带行号与原因；分类不存在不自动创建")
    void importCsvPartialSuccess() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("imp"));

        String csv = "\uFEFF日期,类型,分类,金额,备注\n"
                + "2026-09-05,收入,收取班费,1900.00,全班 38 人\n"
                + "2026-09-06,支出,聚餐,620.00,中秋聚餐\n"
                + "2026-09-07,支出,不存在的分类,10.00,\n"
                + "2026-9-8,收入,收取班费,50.00,日期格式错\n"
                + "2026-09-09,收入,收取班费,-5.00,金额为负\n";

        JsonNode data = upload(token, "流水.csv", csv).path("data");
        assertEquals(2, data.path("success").asInt(), "只有前两行能入");
        JsonNode failed = data.path("failed");
        assertEquals(3, failed.size());
        // 行号是文件行号（含表头那行），和 Excel 左侧一致
        assertEquals(4, failed.get(0).path("line").asInt());
        assertTrue(failed.get(0).path("reason").asText().contains("不存在"));
        assertEquals(5, failed.get(1).path("line").asInt());
        assertTrue(failed.get(1).path("reason").asText().contains("yyyy-MM-dd"));
        assertEquals(6, failed.get(2).path("line").asInt());
        assertTrue(failed.get(2).path("reason").asText().contains("大于 0"));

        // 失败行确实没入库，分类也没被凭空创建
        assertEquals(2, getJson(mockMvc, objectMapper, "/api/transactions", token).path("data").path("total").asInt());
        assertEquals(11, getJson(mockMvc, objectMapper, "/api/categories", token).path("data").size());
    }

    @Test
    @DisplayName("导入的支出受余额约束：余额不够的那一行失败，前面的照常入库")
    void importRespectsBalance() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("imp"));

        String csv = "日期,类型,分类,金额,备注\n"
                + "2026-09-05,收入,收取班费,100.00,\n"
                + "2026-09-06,支出,聚餐,60.00,\n"
                + "2026-09-07,支出,聚餐,80.00,余额只剩 40\n";

        JsonNode data = upload(token, "流水.csv", csv).path("data");
        assertEquals(2, data.path("success").asInt());
        assertEquals(1, data.path("failed").size());
        assertTrue(data.path("failed").get(0).path("reason").asText().contains("余额不足"));
    }

    @Test
    @DisplayName("空文件 / 只有表头：400，不会写库")
    void emptyImportRejected() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("imp"));

        mockMvc.perform(multipart("/api/transactions/import")
                        .file(new MockMultipartFile("file", "空.csv", "text/csv", new byte[0]))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(multipart("/api/transactions/import")
                        .file(new MockMultipartFile("file", "只有表头.csv", "text/csv",
                                "日期,类型,分类,金额,备注\n".getBytes(StandardCharsets.UTF_8)))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("归档账本不能导入：400 只读")
    void archivedBookCannotImport() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("imp"));
        long bookId = getJson(mockMvc, objectMapper, "/api/books", token).path("data").get(0).path("id").asLong();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/books/" + bookId + "/archive")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(200));

        String csv = "日期,类型,分类,金额,备注\n2026-09-05,收入,收取班费,10.00,\n";
        mockMvc.perform(multipart("/api/transactions/import")
                        .file(new MockMultipartFile("file", "流水.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("当前账本已归档（学期结束），只读；先取消归档再记账或改分类"));
    }

    @Test
    @DisplayName("导入只收 CSV：上传 .xlsx 直接 400，并提示先另存为 CSV")
    void importRejectsXlsx() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("xls"));
        record(token, categoryIdOf(token, "收取班费"), "income", "100.00", "2026-09-05", null);

        byte[] xlsx = downloadXlsx(token);
        mockMvc.perform(multipart("/api/transactions/import")
                        .file(new MockMultipartFile("file", "班费.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        "导入只支持 CSV 文件；请在 Excel 里「另存为 → CSV UTF-8（逗号分隔）」后再上传"));
    }

    @Test
    @DisplayName("导出的 CSV 也能原样导入回来（明细表头不在第一行）")
    void exportedCsvCanBeImportedBack() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("xls"));
        record(token, categoryIdOf(token, "收取班费"), "income", "300.00", "2026-09-05", null);

        MvcResult async = mockMvc.perform(get("/api/transactions/export")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(request().asyncStarted())
                .andReturn();
        byte[] csv = mockMvc.perform(asyncDispatch(async)).andReturn().getResponse().getContentAsByteArray();

        JsonNode data = uploadBytes(token, "班费流水_全部.csv", csv).path("data");
        assertEquals(0, data.path("failed").size(), "导出的 CSV 应能导回：" + data);
        assertEquals(1, data.path("success").asInt());
    }

    // ---------- 辅助方法 ----------

    private byte[] downloadXlsx(String token) throws Exception {
        MvcResult async = mockMvc.perform(get("/api/transactions/export?format=xlsx")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(request().asyncStarted())
                .andReturn();
        return mockMvc.perform(asyncDispatch(async)).andReturn().getResponse().getContentAsByteArray();
    }

    private JsonNode uploadBytes(String token, String filename, byte[] content) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/transactions/import")
                        .file(new MockMultipartFile("file", filename,
                                "application/octet-stream", content))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return json(result, objectMapper);
    }

    private JsonNode upload(String token, String filename, String content) throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/transactions/import")
                        .file(new MockMultipartFile("file", filename, "text/csv",
                                content.getBytes(StandardCharsets.UTF_8)))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return json(result, objectMapper);
    }

    private long categoryIdOf(String token, String name) throws Exception {
        return com.bookkeep.web.support.AuthHelper.categoryId(mockMvc, objectMapper, token, name);
    }

    private void record(String token, long categoryId, String type, String amount,
                        String date, String note) throws Exception {
        String body = "{\"categoryId\":" + categoryId + ",\"type\":\"" + type + "\","
                + "\"amount\":\"" + amount + "\",\"transDate\":\"" + date + "\","
                + "\"note\":" + (note == null ? "null" : objectMapper.writeValueAsString(note)) + "}";
        mockMvc.perform(post("/api/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(jsonPath("$.code").value(200));
    }
}