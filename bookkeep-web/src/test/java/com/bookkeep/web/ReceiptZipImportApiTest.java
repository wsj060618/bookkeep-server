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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static com.bookkeep.web.support.AuthHelper.categoryId;
import static com.bookkeep.web.support.AuthHelper.json;
import static com.bookkeep.web.support.AuthHelper.loginData;
import static com.bookkeep.web.support.AuthHelper.nextUsername;
import static com.bookkeep.web.support.AuthHelper.registerAndLogin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 账本包（zip）导入 + 单笔票据 zip 导入的集成测试（PLAN §10.9）。
 *
 * <p>最有价值的用例是**往返**：A 导出 → B 导入，流水笔数/金额/票据张数/落盘月份都要对得上，
 * 这才是「换账号或换机器不丢数据」的真实保证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.receipt.dir=./target/test-receipts")
class ReceiptZipImportApiTest {

    private static final Path ROOT = Paths.get("./target/test-receipts").toAbsolutePath().normalize();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("往返：A 导出的包 B 能导入，流水/金额/票据都对得上，图片落到正确月份")
    void exportedPackageRoundTrips() throws Exception {
        // A：两笔流水，其中一笔两张票据（含一张无票据的流水，验证 CSV 部分也进来了）
        String userA = nextUsername("zipa");
        String tokenA = registerAndLogin(mockMvc, objectMapper, userA);
        long income = create(tokenA, "income", "收取班费", "1900.00", "2026-09-05", "全班38人");
        long expense = create(tokenA, "expense", "聚餐", "62.00", "2026-10-08", "中秋");
        create(tokenA, "expense", "班会物资", "38.00", "2026-10-09", "横幅");   // 没有票据
        upload(tokenA, income, "超市小票.png");
        upload(tokenA, income, "班服收据.png");
        upload(tokenA, expense, "聚餐小票.png");

        // 账本导出包（format=zip）：流水.csv + manifest + 图片，才是可再导入的那种包
        byte[] zip = download("/api/transactions/export?format=zip", tokenA);

        // B：另一个账号，导入同一个包
        String userB = nextUsername("zipb");
        String tokenB = registerAndLogin(mockMvc, objectMapper, userB);
        JsonNode result = json(mockMvc.perform(multipart("/api/transactions/import/zip")
                        .file(new MockMultipartFile("file", "我的班费.zip", "application/zip", zip))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andReturn(), objectMapper);

        assertEquals(200, result.path("code").asInt(), result.toString());
        assertEquals(0, result.path("data").path("failed").size(), result.toString());
        assertEquals(3, result.path("data").path("success").asInt(), "3 笔流水都要进来（含没票据的那笔）");
        assertEquals(3, result.path("data").path("receipts").path("success").asInt());
        assertEquals(0, result.path("data").path("receipts").path("failed").size());

        // 金额一致（用余额校验比逐笔对比更稳，且能抓出「导入丢了某笔」）
        assertEquals("1800.00", com.bookkeep.web.support.AuthHelper
                .getJson(mockMvc, objectMapper, "/api/transactions/balance", tokenB).path("data").asText());

        // 票据张数：3 张；并且 B 的流水行徽标能看到
        JsonNode page = com.bookkeep.web.support.AuthHelper
                .getJson(mockMvc, objectMapper, "/api/transactions", tokenB).path("data").path("list");
        int totalReceipts = 0;
        for (JsonNode row : page) {
            totalReceipts += row.path("receiptCount").asInt();
        }
        assertEquals(3, totalReceipts, "3 张票据都要挂上：" + page);

        // 图片按原月份落盘（不是导入当天）
        long bookB = loginData(mockMvc, objectMapper, userB).path("books").get(0).path("id").asLong();
        try (var walk = Files.walk(ROOT.resolve(bookB + "").getParent())) {
            List<String> paths = walk.filter(Files::isRegularFile)
                    .map(p -> ROOT.relativize(p).toString().replace('\\', '/'))
                    .filter(p -> p.startsWith(bookB + "_") || p.contains("_" + bookB + "/"))
                    .toList();
            assertTrue(paths.stream().anyMatch(p -> p.contains("/2026-09/")), paths.toString());
            assertTrue(paths.stream().anyMatch(p -> p.contains("/2026-10/")), paths.toString());
        }
    }

    @Test
    @DisplayName("包里没有 manifest.csv → 400 并说明要用本系统导出的包")
    void missingManifestIsRejected() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("zip"));
        byte[] zip = zipOf(Map.of(
                "流水.csv", "日期,类型,分类,金额,备注\n2026-09-05,收入,收取班费,10.00,\n".getBytes(StandardCharsets.UTF_8)));

        JsonNode result = json(mockMvc.perform(multipart("/api/transactions/import/zip")
                        .file(new MockMultipartFile("file", "x.zip", "application/zip", zip))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn(), objectMapper);
        assertEquals(400, result.path("code").asInt());
        assertTrue(result.path("message").asText().contains("manifest.csv"), result.path("message").asText());
    }

    @Test
    @DisplayName("单笔票据导入：一个 zip 里多张图，坏图进失败清单，好图挂上")
    void singleTransactionReceiptZip() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("zipt"));
        long txId = create(token, "income", "收取班费", "100.00", "2026-09-05", null);

        byte[] zip = zipOf(Map.of(
                "小票1.png", ReceiptApiTest.png(32),
                "子文件夹/小票2.png", ReceiptApiTest.png(32),
                "说明.txt", "这不是图片".getBytes(StandardCharsets.UTF_8)));

        JsonNode result = json(mockMvc.perform(multipart("/api/transactions/" + txId + "/receipts/import")
                        .file(new MockMultipartFile("file", "票据.zip", "application/zip", zip))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn(), objectMapper);

        assertEquals(200, result.path("code").asInt(), result.toString());
        assertEquals(2, result.path("data").path("success").asInt());
        assertEquals(1, result.path("data").path("failed").size());
        assertTrue(result.path("data").path("failed").get(0).path("file").asText().endsWith("说明.txt"));

        JsonNode list = com.bookkeep.web.support.AuthHelper
                .getJson(mockMvc, objectMapper, "/api/transactions/" + txId + "/receipts", token).path("data");
        assertEquals(2, list.size());
        java.util.Set<String> titles = new java.util.HashSet<>();
        list.forEach(r -> titles.add(r.path("title").asText()));
        assertEquals(java.util.Set.of("小票1", "小票2"), titles, "标题取文件名去扩展名：" + titles);
    }

    @Test
    @DisplayName("路径穿越（../）的条目直接被拒，不会写到临时目录外")
    void traversalEntryIsRejected() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("zipt"));
        long txId = create(token, "income", "收取班费", "100.00", "2026-09-05", null);

        byte[] zip = zipOf(Map.of("../evil.png", ReceiptApiTest.png(16)));
        JsonNode result = json(mockMvc.perform(multipart("/api/transactions/" + txId + "/receipts/import")
                        .file(new MockMultipartFile("file", "bad.zip", "application/zip", zip))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn(), objectMapper);
        assertEquals(400, result.path("code").asInt());
        assertTrue(result.path("message").asText().contains("非法路径"), result.path("message").asText());
    }

    @Test
    @DisplayName("包里用到账本没有的分类：整包不写，返回待新建分类；确认后自动建分类再导")
    void missingCategoriesAskBeforeImporting() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("zip"));

        // 自带 manifest + 流水，分类「打印复印」在默认模板里存在，「班级物品购买」不存在
        String flow = "日期,类型,分类,金额,备注\n"
                + "2024-12-12,支出,打印复印,16.00,英语听力资料打印\n"
                + "2025-02-24,支出,班级物品购买,114.03,物理本子\n";
        String manifest = "日期,类型,分类,金额,备注,票据标题,相对路径\n"
                + "2025-02-24,支出,班级物品购买,114.03,物理本子,本子小票,img/本子.png\n";
        // 包里带的分类模板 JSON：新分类「班级物品购买」本来是紫色 + 奖品图标
        String template = "{\"version\":1,\"name\":\"计科三班\",\"items\":["
                + "{\"name\":\"班级物品购买\",\"type\":\"expense\",\"icon\":\"prize\",\"color\":\"purple\","
                + "\"sortOrder\":1}]}";
        byte[] zip = zipOf(Map.of(
                "流水.csv", flow.getBytes(StandardCharsets.UTF_8),
                "manifest.csv", manifest.getBytes(StandardCharsets.UTF_8),
                "分类模板.json", template.getBytes(StandardCharsets.UTF_8),
                "img/本子.png", ReceiptApiTest.png(32)));

        // 1) 不确认：一行都不写，只回「缺哪个分类」
        JsonNode first = json(mockMvc.perform(multipart("/api/transactions/import/zip")
                        .file(new MockMultipartFile("file", "包.zip", "application/zip", zip))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn(), objectMapper).path("data");
        assertEquals(0, first.path("success").asInt());
        JsonNode missing = first.path("missingCategories");
        assertEquals(1, missing.size(), first.toString());
        assertEquals("班级物品购买", missing.get(0).path("name").asText());
        assertEquals("expense", missing.get(0).path("type").asText());
        assertEquals(0, com.bookkeep.web.support.AuthHelper
                .getJson(mockMvc, objectMapper, "/api/transactions", token).path("data").path("total").asInt(),
                "问用户之前不能先导一半");

        // 2) 确认：带上 createMissing，分类自动建好，两笔流水 + 一张票据全进来
        JsonNode second = json(mockMvc.perform(multipart("/api/transactions/import/zip")
                        .file(new MockMultipartFile("file", "包.zip", "application/zip", zip))
                        .param("createMissing", "true")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn(), objectMapper).path("data");
        assertEquals(0, second.path("missingCategories").size(), second.toString());
        assertEquals(0, second.path("failed").size(), second.toString());
        assertEquals(2, second.path("success").asInt());
        assertEquals(1, second.path("receipts").path("success").asInt());

        // 新建的分类进了分类列表（也补进了模板，所以以后新账本也有它），样式取自包里的 分类模板.json
        JsonNode categories = com.bookkeep.web.support.AuthHelper
                .getJson(mockMvc, objectMapper, "/api/categories?type=expense", token).path("data");
        assertTrue(categories.toString().contains("班级物品购买"), categories.toString());
        JsonNode created = null;
        for (JsonNode category : categories) {
            if ("班级物品购买".equals(category.path("name").asText())) {
                created = category;
            }
        }
        assertTrue(created != null, categories.toString());
        assertEquals("prize", created.path("icon").asText(), "图标来自包内的 分类模板.json");
        assertEquals("purple", created.path("color").asText(), "颜色来自包内的 分类模板.json");
    }

    /* ---------- 工具 ---------- */

    private long create(String token, String type, String category, String amount, String date, String note)
            throws Exception {
        long categoryId = categoryId(mockMvc, objectMapper, token, category);
        MvcResult result = mockMvc.perform(post("/api/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"categoryId\":" + categoryId + ",\"type\":\"" + type
                                + "\",\"amount\":\"" + amount + "\",\"transDate\":\"" + date + "\","
                                + "\"note\":" + (note == null ? "null" : objectMapper.writeValueAsString(note)) + "}"))
                .andReturn();
        return json(result, objectMapper).path("data").path("id").asLong();
    }

    private void upload(String token, long txId, String filename) throws Exception {
        mockMvc.perform(multipart("/api/transactions/" + txId + "/receipts")
                        .file(new MockMultipartFile("file", filename, "image/png", ReceiptApiTest.png(32)))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    private byte[] download(String url, String token) throws Exception {
        MvcResult started = mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(request().asyncStarted())
                .andReturn();
        return mockMvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsByteArray();
    }

    /** 在内存里造一个 zip（测试用） */
    private static byte[] zipOf(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** 读 zip 的条目名（排查用） */
    private static List<String> entryNames(byte[] zip) throws IOException {
        List<String> names = new java.util.ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }
}