package com.bookkeep.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.bookkeep.web.support.AuthHelper.getJson;
import static com.bookkeep.web.support.AuthHelper.json;
import static com.bookkeep.web.support.AuthHelper.loginData;
import static com.bookkeep.web.support.AuthHelper.nextUsername;
import static com.bookkeep.web.support.AuthHelper.registerAndLogin;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 票据接口集成测试（PLAN §10.9）。
 * <p>白盒关注点：归属校验（越权必须 404 而不是 403）、魔数识别（改扩展名的假图要拒）、
 * 上限（10MB / 9 张）、归档账本只读、删除时连文件一起删。
 * <p>文件落在 {@code target/test-receipts}：测试回滚只回滚数据库，磁盘上的文件不会自己消失，
 * 放在 target 下才不会污染仓库（每次 {@code mvn clean} 一起清掉）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.receipt.dir=./target/test-receipts")
class ReceiptApiTest {

    private static final Path ROOT = Paths.get("./target/test-receipts").toAbsolutePath().normalize();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /* ---------- 正常路径 ---------- */

    @Test
    @DisplayName("上传票据：默认标题取文件名，列表返回元数据，列表接口带票据数量")
    void uploadUsesFileNameAsDefaultTitle() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("rcp"));
        long txId = income(token, "1900.00", "2026-09-05");

        JsonNode data = json(upload(token, txId, "超市小票.png", png(64), null), objectMapper).path("data");
        assertEquals("超市小票", data.path("title").asText(), "空标题应回落文件名去扩展名");
        assertEquals(64, data.path("sizeBytes").asLong());
        assertFalse(data.has("storedPath"), "响应里绝不能出现磁盘路径");

        JsonNode list = getJson(mockMvc, objectMapper,
                "/api/transactions/" + txId + "/receipts", token).path("data");
        assertEquals(1, list.size());
        assertEquals("超市小票", list.get(0).path("title").asText());

        // 流水列表的徽标数据：同一页一次查询拿到的张数
        JsonNode page = getJson(mockMvc, objectMapper, "/api/transactions", token).path("data");
        assertEquals(1, page.path("list").get(0).path("receiptCount").asInt());
    }

    @Test
    @DisplayName("下载：inline + nosniff，内容与上传字节一致")
    void downloadReturnsInlineImage() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("rcp"));
        long txId = income(token, "100.00", "2026-09-05");
        byte[] bytes = png(200);
        long receiptId = json(upload(token, txId, "小票.png", bytes, "班服收据"), objectMapper)
                .path("data").path("id").asLong();

        MvcResult result = mockMvc.perform(get("/api/transactions/" + txId + "/receipts/" + receiptId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();

        assertTrue(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION).startsWith("inline"));
        assertArrayEquals(bytes, result.getResponse().getContentAsByteArray());
    }

    @Test
    @DisplayName("自定义标题 + 改名：超 30 字与空标题都 400")
    void renameValidatesTitle() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("rcp"));
        long txId = income(token, "100.00", "2026-09-05");
        long receiptId = json(upload(token, txId, "a.png", png(32), "自定义标题"), objectMapper)
                .path("data").path("id").asLong();
        assertEquals("自定义标题", jdbcTemplate.queryForObject(
                "SELECT title FROM transaction_receipt WHERE id = ?", String.class, receiptId));

        mockMvc.perform(put("/api/transactions/" + txId + "/receipts/" + receiptId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"title\":\"新标题\"}"))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals("新标题", jdbcTemplate.queryForObject(
                "SELECT title FROM transaction_receipt WHERE id = ?", String.class, receiptId));

        mockMvc.perform(put("/api/transactions/" + txId + "/receipts/" + receiptId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"title\":\"" + "标".repeat(31) + "\"}"))
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(put("/api/transactions/" + txId + "/receipts/" + receiptId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"title\":\"   \"}"))
                .andExpect(jsonPath("$.code").value(400));
    }

    /* ---------- 校验与上限 ---------- */

    @Test
    @DisplayName("魔数识别：改扩展名的假图片 400（SVG 也在这一步被挡）")
    void rejectNonImageByMagicNumber() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("rcp"));
        long txId = income(token, "100.00", "2026-09-05");

        JsonNode body = json(upload(token, txId, "伪装.jpg",
                "<svg onload=alert(1)></svg>".getBytes(StandardCharsets.UTF_8), null), objectMapper);
        assertEquals(400, body.path("code").asInt());
        assertTrue(body.path("message").asText().contains("只支持"), body.path("message").asText());
        assertEquals(0, getJson(mockMvc, objectMapper,
                "/api/transactions/" + txId + "/receipts", token).path("data").size());
    }

    @Test
    @DisplayName("单张超 10MB → 400")
    void rejectOversizedFile() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("rcp"));
        long txId = income(token, "100.00", "2026-09-05");
        byte[] tooBig = png(10 * 1024 * 1024 + 1);

        JsonNode body = json(upload(token, txId, "big.png", tooBig, null), objectMapper);
        assertEquals(400, body.path("code").asInt());
        assertTrue(body.path("message").asText().contains("10MB"), body.path("message").asText());
    }

    @Test
    @DisplayName("单笔最多 9 张：第 10 张 400")
    void maxNinePerTransaction() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("rcp"));
        long txId = income(token, "100.00", "2026-09-05");
        for (int i = 1; i <= 9; i++) {
            assertEquals(200, json(upload(token, txId, "图" + i + ".png", png(16), null), objectMapper)
                    .path("code").asInt(), "第 " + i + " 张应成功");
        }
        JsonNode tenth = json(upload(token, txId, "第10张.png", png(16), null), objectMapper);
        assertEquals(400, tenth.path("code").asInt());
        assertTrue(tenth.path("message").asText().contains("9"), tenth.path("message").asText());
    }

    /* ---------- 越权 ---------- */

    @Test
    @DisplayName("越权：B 拿不到 A 的流水，也挂不上票据（404，不是 403）")
    void cannotTouchOtherUsersTransaction() throws Exception {
        String tokenA = registerAndLogin(mockMvc, objectMapper, nextUsername("rcpa"));
        long txIdA = income(tokenA, "100.00", "2026-09-05");

        String tokenB = registerAndLogin(mockMvc, objectMapper, nextUsername("rcpb"));
        JsonNode uploadBody = json(upload(tokenB, txIdA, "偷看.png", png(16), null), objectMapper);
        assertEquals(404, uploadBody.path("code").asInt());

        assertEquals(404, getJson(mockMvc, objectMapper,
                "/api/transactions/" + txIdA + "/receipts", tokenB).path("code").asInt());
    }

    @Test
    @DisplayName("票据与流水必须对得上：拿 A 笔的票据 id 去 B 笔的路径 → 404")
    void receiptMustBelongToThatTransaction() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("rcp"));
        long tx1 = income(token, "100.00", "2026-09-05");
        long tx2 = income(token, "200.00", "2026-09-06");
        long receiptId = json(upload(token, tx1, "小票.png", png(16), null), objectMapper)
                .path("data").path("id").asLong();

        assertEquals(404, json(mockMvc.perform(get("/api/transactions/" + tx2 + "/receipts/" + receiptId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn(), objectMapper)
                .path("code").asInt());

        assertEquals(404, json(mockMvc.perform(delete("/api/transactions/" + tx2 + "/receipts/" + receiptId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn(), objectMapper)
                .path("code").asInt(), "不能借另一笔的 id 删掉这张票据");
    }

    /* ---------- 归档账本 ---------- */

    @Test
    @DisplayName("归档账本：能看能下，不能上传/删除")
    void archivedBookIsReadOnly() throws Exception {
        String username = nextUsername("rcp");
        String token = registerAndLogin(mockMvc, objectMapper, username);
        long bookId = loginData(mockMvc, objectMapper, username).path("books").get(0).path("id").asLong();
        long txId = income(token, "100.00", "2026-09-05");
        long receiptId = json(upload(token, txId, "小票.png", png(16), null), objectMapper)
                .path("data").path("id").asLong();

        mockMvc.perform(put("/api/books/" + bookId + "/archive")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(400, json(upload(token, txId, "新增.png", png(16), null), objectMapper)
                .path("code").asInt(), "归档后不允许再上传");
        assertEquals(400, json(mockMvc.perform(delete("/api/transactions/" + txId + "/receipts/" + receiptId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn(), objectMapper)
                .path("code").asInt(), "归档后不允许删除");

        mockMvc.perform(get("/api/transactions/" + txId + "/receipts/" + receiptId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    /* ---------- 删除 ---------- */

    @Test
    @DisplayName("删除票据：库记录与磁盘文件一起消失")
    void deleteRemovesRecordAndFile() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("rcp"));
        long txId = income(token, "100.00", "2026-09-05");
        long receiptId = json(upload(token, txId, "要删的.png", png(16), null), objectMapper)
                .path("data").path("id").asLong();

        String storedPath = jdbcTemplate.queryForObject(
                "SELECT stored_path FROM transaction_receipt WHERE id = ?", String.class, receiptId);
        Path file = ROOT.resolve(storedPath);
        assertTrue(Files.exists(file), "上传后文件应在磁盘上：" + file);

        mockMvc.perform(delete("/api/transactions/" + txId + "/receipts/" + receiptId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(200));

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transaction_receipt WHERE id = ?", Integer.class, receiptId));
        assertFalse(Files.exists(file), "删除后文件也应消失：" + file);
    }

    @Test
    @DisplayName("彻底删除账本：票据库记录与文件一起删，不留 join 不到的孤儿行")
    void purgingBookDeletesItsReceipts() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("rcp"));
        long txId = income(token, "100.00", "2026-09-05");
        long receiptId = json(upload(token, txId, "孤儿.png", png(32), null), objectMapper)
                .path("data").path("id").asLong();
        String storedPath = jdbcTemplate.queryForObject(
                "SELECT stored_path FROM transaction_receipt WHERE id = ?", String.class, receiptId);
        long oldBookId = json(mockMvc.perform(get("/api/books")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn(), objectMapper)
                .path("data").get(0).path("id").asLong();

        // 新建一本（自动切过去）→ 把原来那本删进回收站 → 彻底删除
        mockMvc.perform(post("/api/books")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"name\":\"新账本\"}"))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(delete("/api/books/" + oldBookId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(delete("/api/books/" + oldBookId + "/permanent")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(200));

        // hardDeleteByBook 是 join `transaction` 定位的，删流水之前不删票据就永远是孤儿行
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transaction_receipt WHERE transaction_id = ?", Integer.class, txId),
                "账本彻底删除后，它名下流水的票据记录也要一起删");
        assertFalse(Files.exists(ROOT.resolve(storedPath)), "票据文件也应随之删除");
    }

    /* ---------- 工具 ---------- */

    private MockMultipartHttpServletRequestBuilder multipartUpload(String token, long txId) {
        return (MockMultipartHttpServletRequestBuilder) multipart("/api/transactions/" + txId + "/receipts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private MvcResult upload(String token, long txId, String filename, byte[] bytes, String title)
            throws Exception {
        var builder = multipartUpload(token, txId)
                .file(new MockMultipartFile("file", filename, "image/png", bytes));
        if (title != null) {
            builder.param("title", title);
        }
        return mockMvc.perform(builder).andReturn();
    }

    private long income(String token, String amount, String date) throws Exception {
        long categoryId = com.bookkeep.web.support.AuthHelper.categoryId(mockMvc, objectMapper, token, "收取班费");
        MvcResult result = mockMvc.perform(post("/api/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"categoryId\":" + categoryId + ",\"type\":\"income\",\"amount\":\"" + amount
                                + "\",\"transDate\":\"" + date + "\"}"))
                .andReturn();
        return json(result, objectMapper).path("data").path("id").asLong();
    }

    /** 合法 PNG 头 + 填充；接口只按魔数判断，不需要真的能解码 */
    static byte[] png(int size) {
        byte[] bytes = new byte[Math.max(size, 12)];
        bytes[0] = (byte) 0x89;
        bytes[1] = 'P';
        bytes[2] = 'N';
        bytes[3] = 'G';
        bytes[4] = 0x0D;
        bytes[5] = 0x0A;
        bytes[6] = 0x1A;
        bytes[7] = 0x0A;
        return bytes;
    }
}