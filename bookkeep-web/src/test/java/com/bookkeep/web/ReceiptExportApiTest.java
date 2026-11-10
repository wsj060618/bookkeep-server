package com.bookkeep.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.bookkeep.web.support.AuthHelper.categoryId;
import static com.bookkeep.web.support.AuthHelper.json;
import static com.bookkeep.web.support.AuthHelper.loginData;
import static com.bookkeep.web.support.AuthHelper.nextUsername;
import static com.bookkeep.web.support.AuthHelper.registerAndLogin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 票据 zip 导出集成测试（PLAN §10.9）。
 * <p>两种入口共用同一套目录规则：**账本级**（可带日期区间）与**单笔**。
 * <p>白盒关注：zip 内目录层级（账本/月份）、文件名规则（日期_标题）、同目录重名自动 _2、
 * manifest.csv 的行数与相对路径、以及「没票据 / 越权 / 区间反了」仍返回正常的 200+JSON 信封。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.receipt.dir=./target/test-receipts")
class ReceiptExportApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("账本级导出：zip 内按 账本名_bookId/月份 分层，重名加 _2，manifest 行数对齐")
    void exportBookZipLayout() throws Exception {
        Fixture f = new Fixture("3班班费");
        long sep = f.create("income", "收取班费", "1900.00", "2026-09-05");
        long oct = f.create("expense", "聚餐", "62.00", "2026-10-08");

        f.upload(sep, "超市小票.png");
        f.upload(oct, "聚餐小票.png");
        // 同一天同一个标题 → 第二张必须加 _2，不能互相覆盖
        f.upload(oct, "聚餐小票.png");

        MvcResult result = download("/api/transactions/receipts/export", f.token);
        assertEquals("application/zip", result.getResponse().getContentType());
        String filename = dispositionFilename(result);
        assertTrue(filename.endsWith("_票据_全部.zip"), filename);

        Map<String, byte[]> entries = unzip(result.getResponse().getContentAsByteArray());
        String bookDir = "3班班费_" + f.bookId;
        assertTrue(entries.containsKey("manifest.csv"), entries.keySet().toString());
        assertTrue(entries.containsKey(bookDir + "/2026-09/2026-09-05_超市小票.png"), entries.keySet().toString());
        assertTrue(entries.containsKey(bookDir + "/2026-10/2026-10-08_聚餐小票.png"), entries.keySet().toString());
        assertTrue(entries.containsKey(bookDir + "/2026-10/2026-10-08_聚餐小票_2.png"), entries.keySet().toString());

        String manifest = new String(entries.get("manifest.csv"), StandardCharsets.UTF_8);
        assertTrue(manifest.startsWith("\uFEFF日期,类型,分类,金额,备注,票据标题,相对路径"), "manifest 表头或 BOM 不对");
        String[] lines = manifest.replace("\uFEFF", "").split("\n");
        assertEquals(4, lines.length, "1 行表头 + 3 行票据，实际：\n" + manifest);
        assertTrue(lines[1].contains("收入") && lines[1].contains("1900.00"), lines[1]);
        assertTrue(lines[1].contains(bookDir + "/2026-09/2026-09-05_超市小票.png"), lines[1]);
    }

    @Test
    @DisplayName("日期区间：只打区间内的票据，文件名变成「_部分_起-止」")
    void exportBookRangeFilters() throws Exception {
        Fixture f = new Fixture("3班班费");
        long sep = f.create("income", "收取班费", "100.00", "2026-09-05");
        long oct = f.create("expense", "聚餐", "50.00", "2026-10-08");
        f.upload(sep, "九月.png");
        f.upload(oct, "十月.png");

        MvcResult result = download(
                "/api/transactions/receipts/export?startDate=2026-09-01&endDate=2026-09-30", f.token);
        String filename = dispositionFilename(result);
        assertTrue(filename.contains("_部分_20260901-20260930.zip"), filename);

        Map<String, byte[]> entries = unzip(result.getResponse().getContentAsByteArray());
        assertTrue(entries.keySet().stream().anyMatch(n -> n.endsWith("九月.png")), entries.keySet().toString());
        assertFalse(entries.keySet().stream().anyMatch(n -> n.endsWith("十月.png")), entries.keySet().toString());
    }

    @Test
    @DisplayName("单笔导出：只打这一笔，文件名带日期/分类/金额")
    void exportSingleTransactionZip() throws Exception {
        Fixture f = new Fixture("3班班费");
        long sep = f.create("income", "收取班费", "1900.00", "2026-09-05");
        long oct = f.create("expense", "聚餐", "62.00", "2026-10-08");
        f.upload(sep, "九月小票.png");
        f.upload(oct, "十月小票.png");

        MvcResult result = download("/api/transactions/" + sep + "/receipts/export", f.token);
        String filename = dispositionFilename(result);
        assertTrue(filename.contains("20260905") && filename.contains("1900.00") && filename.endsWith(".zip"),
                filename);

        Map<String, byte[]> entries = unzip(result.getResponse().getContentAsByteArray());
        assertEquals(2, entries.size(), "单笔导出 = manifest + 这一笔的 1 张图：" + entries.keySet());
        assertFalse(entries.keySet().stream().anyMatch(n -> n.endsWith("十月小票.png")), entries.keySet().toString());
    }

    @Test
    @DisplayName("磁盘上缺了图片：跳过该文件，manifest 里标「文件缺失」，其余照常打包")
    void missingFileIsSkippedAndMarked() throws Exception {
        Fixture f = new Fixture("3班班费");
        long txId = f.create("income", "收取班费", "100.00", "2026-09-05");
        f.upload(txId, "还在的.png");
        f.upload(txId, "会消失的.png");

        // 从磁盘删掉其中一张，模拟「只恢复了数据库、没恢复票据卷」
        List<String> stored = jdbcTemplate.queryForList(
                "SELECT r.stored_path FROM transaction_receipt r "
                        + "JOIN `transaction` t ON t.id = r.transaction_id WHERE t.book_id = ?",
                String.class, f.bookId);
        assertEquals(2, stored.size());
        Path root = Paths.get("./target/test-receipts").toAbsolutePath().normalize();
        Files.delete(root.resolve(stored.get(0)));

        MvcResult result = download("/api/transactions/receipts/export", f.token);
        Map<String, byte[]> entries = unzip(result.getResponse().getContentAsByteArray());

        assertEquals(2, entries.size(), "manifest + 剩下那一张，实际：" + entries.keySet());
        String manifest = new String(entries.get("manifest.csv"), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("文件缺失"), "manifest 要标出缺了哪个：" + manifest);
        assertEquals(2, manifest.replace("\uFEFF", "").trim().split("\n").length - 1,
                "缺文件的那行也要留在 manifest 里，方便人工核对");
    }

    @Test
    @DisplayName("没票据 / 区间反了 / 越权：都返回 200+JSON 信封，绝不给空 zip")
    void invalidRequestsReturnJsonEnvelope() throws Exception {
        Fixture f = new Fixture("3班班费");
        long txId = f.create("income", "收取班费", "100.00", "2026-09-05");

        JsonNode noReceipts = json(mockMvc.perform(get("/api/transactions/receipts/export")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.token)).andReturn(), objectMapper);
        assertEquals(400, noReceipts.path("code").asInt());
        assertTrue(noReceipts.path("message").asText().contains("没有"), noReceipts.path("message").asText());

        JsonNode badRange = json(mockMvc.perform(get(
                        "/api/transactions/receipts/export?startDate=2026-10-01&endDate=2026-09-01")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + f.token)).andReturn(), objectMapper);
        assertEquals(400, badRange.path("code").asInt());
        assertTrue(badRange.path("message").asText().contains("不能晚于"), badRange.path("message").asText());

        String other = registerAndLogin(mockMvc, objectMapper, nextUsername("rcpz"));
        JsonNode foreign = json(mockMvc.perform(get("/api/transactions/" + txId + "/receipts/export")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + other)).andReturn(), objectMapper);
        assertEquals(404, foreign.path("code").asInt());
    }

    /* ---------- 工具 ---------- */

    /** 一套「注册 + 改账本名 + 建流水 + 传图」的固定动作，省掉每个用例重复造数据 */
    private class Fixture {

        private final String token;
        private final long bookId;

        Fixture(String bookName) throws Exception {
            String username = nextUsername("rcpx");
            this.token = registerAndLogin(mockMvc, objectMapper, username);
            this.bookId = loginData(mockMvc, objectMapper, username).path("books").get(0).path("id").asLong();
            mockMvc.perform(put("/api/books/" + bookId)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType("application/json")
                            .content("{\"name\":\"" + bookName + "\"}"))
                    .andExpect(status().isOk());
            // 先垫一笔大额收入：支出的余额校验需要账上有钱（没有票据，不影响任何导出断言）
            create("income", "收取班费", "10000.00", "2026-08-01");
        }

        long create(String type, String category, String amount, String date) throws Exception {
            long categoryId = categoryId(mockMvc, objectMapper, token, category);
            MvcResult result = mockMvc.perform(post("/api/transactions")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType("application/json")
                            .content("{\"categoryId\":" + categoryId + ",\"type\":\"" + type
                                    + "\",\"amount\":\"" + amount + "\",\"transDate\":\"" + date + "\"}"))
                    .andReturn();
            return json(result, objectMapper).path("data").path("id").asLong();
        }

        void upload(long txId, String filename) throws Exception {
            mockMvc.perform(multipart("/api/transactions/" + txId + "/receipts")
                            .file(new MockMultipartFile("file", filename, "image/png", ReceiptApiTest.png(32)))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk());
        }
    }

    /** 流式响应必须先 asyncStarted 再 asyncDispatch，才能拿到最终响应体 */
    private MvcResult download(String url, String token) throws Exception {
        MvcResult started = mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(request().asyncStarted())
                .andReturn();
        return mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn();
    }

    private String dispositionFilename(MvcResult result) {
        String header = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        int idx = header == null ? -1 : header.indexOf("filename*=UTF-8''");
        if (idx < 0) {
            return header;
        }
        return URLDecoder.decode(header.substring(idx + "filename*=UTF-8''".length()), StandardCharsets.UTF_8);
    }

    private Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        return entries;
    }
}