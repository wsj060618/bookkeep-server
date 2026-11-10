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
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static com.bookkeep.web.support.AuthHelper.categoryId;
import static com.bookkeep.web.support.AuthHelper.json;
import static com.bookkeep.web.support.AuthHelper.loginData;
import static com.bookkeep.web.support.AuthHelper.nextUsername;
import static com.bookkeep.web.support.AuthHelper.registerAndLogin;
import static com.bookkeep.web.support.AuthHelper.shareToken;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公示页票据的集成测试（PLAN §10.9）——**故意不带 Authorization**，免登录就是这条路的契约。
 * <p>白盒关注四条校验缺一不可：token 有效 + 账本未删 + 流水未删 + 票据属于该 token 的账本；
 * 任一不满足都必须是 404（不能 403/400 暴露「存在但无权」）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.receipt.dir=./target/test-receipts")
class PublicReceiptApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("公示页每笔流水带票据 id + 标题，且不泄露原始文件名/路径")
    void reportCarriesReceiptsWithoutPaths() throws Exception {
        String username = nextUsername("pubr");
        String token = registerAndLogin(mockMvc, objectMapper, username);
        long txId = income(token, "1900.00");
        upload(token, txId, "超市小票.png", "班服收据");

        String share = shareToken(mockMvc, objectMapper, username);
        JsonNode report = json(mockMvc.perform(get("/api/public/report/" + share)).andReturn(), objectMapper);
        assertEquals(200, report.path("code").asInt());

        JsonNode row = report.path("data").path("books").get(0).path("transactions").get(0);
        assertEquals(1, row.path("receiptCount").asInt());
        assertEquals("班服收据", row.path("receipts").get(0).path("title").asText());
        assertFalse(row.path("receipts").get(0).has("originalName"), "公示页不给原始文件名");
        assertFalse(row.path("receipts").get(0).has("storedPath"), "公示页不给磁盘路径");
    }

    @Test
    @DisplayName("匿名访客能直接看图：inline + nosniff")
    void anonymousCanLoadImage() throws Exception {
        String username = nextUsername("pubr");
        String token = registerAndLogin(mockMvc, objectMapper, username);
        long txId = income(token, "1900.00");
        long receiptId = upload(token, txId, "小票.png", null);
        String share = shareToken(mockMvc, objectMapper, username);

        mockMvc.perform(get("/api/public/report/" + share + "/receipts/" + receiptId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("inline")));
    }

    @Test
    @DisplayName("链接过期后：报告与图片都 404（同一个时钟，不分两条路）")
    void expiredLinkCannotReadReceipt() throws Exception {
        String username = nextUsername("pubr");
        String token = registerAndLogin(mockMvc, objectMapper, username);
        long txId = income(token, "1900.00");
        long receiptId = upload(token, txId, "小票.png", null);

        String share = shareToken(mockMvc, objectMapper, username);
        jdbcTemplate.update("UPDATE share_link SET expires_at = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE token = ?",
                share);

        assertEquals(404, json(mockMvc.perform(get("/api/public/report/" + share)).andReturn(), objectMapper)
                .path("code").asInt());
        assertEquals(404, json(mockMvc.perform(get("/api/public/report/" + share + "/receipts/" + receiptId))
                .andReturn(), objectMapper).path("code").asInt());
    }

    @Test
    @DisplayName("跨账本：A 的链接打不开 B 的票据（404，不是 403）")
    void cannotReadAnotherBooksReceipt() throws Exception {
        String userA = nextUsername("pubra");
        registerAndLogin(mockMvc, objectMapper, userA);

        String userB = nextUsername("pubrb");
        String tokenB = registerAndLogin(mockMvc, objectMapper, userB);
        long txB = income(tokenB, "500.00");
        long receiptB = upload(tokenB, txB, "B的小票.png", null);

        String shareA = shareToken(mockMvc, objectMapper, userA);
        assertEquals(404, json(mockMvc.perform(get("/api/public/report/" + shareA + "/receipts/" + receiptB))
                .andReturn(), objectMapper).path("code").asInt());
    }

    @Test
    @DisplayName("流水进回收站后，公示页看不到它，图片也 404")
    void deletedTransactionHidesReceipt() throws Exception {
        String username = nextUsername("pubr");
        String token = registerAndLogin(mockMvc, objectMapper, username);
        long txId = income(token, "1900.00");
        long receiptId = upload(token, txId, "小票.png", null);
        String share = shareToken(mockMvc, objectMapper, username);

        mockMvc.perform(delete("/api/transactions/" + txId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        JsonNode report = json(mockMvc.perform(get("/api/public/report/" + share)).andReturn(), objectMapper);
        assertEquals(0, report.path("data").path("books").get(0).path("transactions").size(),
                "删掉的流水不该出现在公示页");
        assertEquals(404, json(mockMvc.perform(get("/api/public/report/" + share + "/receipts/" + receiptId))
                .andReturn(), objectMapper).path("code").asInt());
    }

    @Test
    @DisplayName("猜一个不存在的票据 id 也是 404")
    void unknownReceiptIs404() throws Exception {
        String username = nextUsername("pubr");
        registerAndLogin(mockMvc, objectMapper, username);
        String share = shareToken(mockMvc, objectMapper, username);

        assertEquals(404, json(mockMvc.perform(get("/api/public/report/" + share + "/receipts/99999999"))
                .andReturn(), objectMapper).path("code").asInt());
    }

    /* ---------- 工具 ---------- */

    private long income(String token, String amount) throws Exception {
        long categoryId = categoryId(mockMvc, objectMapper, token, "收取班费");
        MvcResult result = mockMvc.perform(post("/api/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"categoryId\":" + categoryId + ",\"type\":\"income\",\"amount\":\"" + amount
                                + "\",\"transDate\":\"" + LocalDate.now() + "\"}"))
                .andReturn();
        return json(result, objectMapper).path("data").path("id").asLong();
    }

    private long upload(String token, long txId, String filename, String title) throws Exception {
        var builder = (org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder)
                multipart("/api/transactions/" + txId + "/receipts")
                        .file(new MockMultipartFile("file", filename, "image/png", ReceiptApiTest.png(32)))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (title != null) {
            builder.param("title", title);
        }
        MvcResult result = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
        JsonNode body = json(result, objectMapper);
        assertTrue(body.path("code").asInt() == 200,
                new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
        return body.path("data").path("id").asLong();
    }
}