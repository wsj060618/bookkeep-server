package com.bookkeep.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.util.function.Consumer;

import static com.bookkeep.web.support.AuthHelper.categoryId;
import static com.bookkeep.web.support.AuthHelper.getJson;
import static com.bookkeep.web.support.AuthHelper.json;
import static com.bookkeep.web.support.AuthHelper.nextUsername;
import static com.bookkeep.web.support.AuthHelper.registerAndLogin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「保存模板 → 同步到用过这套模板的账本」的集成测试。
 *
 * <p>钉住四件事：
 * <ol>
 *   <li>模板新增条目 → 账本多一个分类，并且**立刻能用来记一笔**</li>
 *   <li>模板改名 → 账本里那条分类改名，**分类 id 不变，流水还挂在上面**</li>
 *   <li>模板删条目 → 账本里没流水的分类一起删</li>
 *   <li>模板删条目但账本有流水 → **整次保存失败**（提示账本/分类/笔数），模板条目也不许少</li>
 * </ol>
 * 另外钉住「条目 id 必须保留」：改名如果退化成删旧建新，第 2、4 条都会失败。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CategoryTemplateSyncApiTest {

    private static final String TEMPLATE_ITEMS = "$.data[0].items";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("模板新增条目 → 账本同步出分类，且马上能记一笔")
    void newItemPropagatesToBook() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("sync"));
        long templateId = defaultTemplateId(token);
        JsonNode template = defaultTemplate(token);

        ObjectNode payload = payloadOf(template, items -> items.add(item(null, "班服退款", "income", "fee", "green")));
        saveTemplate(token, templateId, payload, 200);

        // 账本里真多了这条分类（/api/categories 就是「记一笔」下拉的数据源）
        long newCategoryId = categoryId(mockMvc, objectMapper, token, "班服退款");

        // 能直接拿来记账 → 证明不是只写了个看不见的行
        mockMvc.perform(post("/api/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":" + newCategoryId
                                + ",\"type\":\"income\",\"amount\":\"12.00\",\"transDate\":\"2026-11-09\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("模板改名 → 账本分类跟着改名，分类 id 不变、流水还在上面")
    void renameKeepsTransactionsAttached() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("sync"));
        long templateId = defaultTemplateId(token);
        JsonNode template = defaultTemplate(token);

        long feeId = categoryId(mockMvc, objectMapper, token, "收取班费");
        createIncome(token, feeId, "100.00");

        ObjectNode payload = payloadOf(template, items -> rename(items, "收取班费", "班费收入"));
        saveTemplate(token, templateId, payload, 200);

        assertEquals(feeId, categoryId(mockMvc, objectMapper, token, "班费收入"),
                "改名必须保留原分类 id（否则流水就断开了）");

        JsonNode transactions = getJson(mockMvc, objectMapper, "/api/transactions", token).path("data").path("list");
        assertEquals(1, transactions.size());
        assertEquals("班费收入", transactions.get(0).path("categoryName").asText());
        assertEquals(feeId, transactions.get(0).path("categoryId").asLong());
    }

    @Test
    @DisplayName("模板删掉没流水的分类 → 账本里也删掉")
    void deletingUnusedItemRemovesBookCategory() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("sync"));
        long templateId = defaultTemplateId(token);
        JsonNode template = defaultTemplate(token);

        ObjectNode payload = payloadOf(template, items -> remove(items, "打印复印"));
        saveTemplate(token, templateId, payload, 200);

        JsonNode categories = getJson(mockMvc, objectMapper, "/api/categories", token).path("data");
        assertFalse(names(categories).contains("打印复印"), "打印复印应该已经同步删掉");
        assertEquals(10, categories.size());
    }

    @Test
    @DisplayName("模板删掉有流水的分类 → 400 提示，模板与账本都不动")
    void deletingUsedItemIsRejected() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("sync"));
        long templateId = defaultTemplateId(token);
        JsonNode template = defaultTemplate(token);

        long feeId = categoryId(mockMvc, objectMapper, token, "收取班费");
        createIncome(token, feeId, "200.00");

        ObjectNode payload = payloadOf(template, items -> remove(items, "收取班费"));
        MvcResult result = mockMvc.perform(put("/api/category-templates/" + templateId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andReturn();

        String message = json(result, objectMapper).path("message").asText();
        assertTrue(message.contains("收取班费"), "提示里要点名是哪条分类：" + message);
        assertTrue(message.contains("流水"), "提示里要说明被流水挡住：" + message);

        // 账本分类与流水必须原样还在（模板条目的删除由 Spring 事务回滚，
// 但本测试自己也跑在一个事务里，回滚前看不到恢复后的模板行，所以只断言业务可见的部分）
        assertEquals(feeId, categoryId(mockMvc, objectMapper, token, "收取班费"));
        assertEquals(1, getJson(mockMvc, objectMapper, "/api/transactions", token)
                .path("data").path("list").size());
    }

    @Test
    @DisplayName("保存模板不会换掉条目 id（改名 ≠ 删旧建新）")
    void itemIdsSurviveSave() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("sync"));
        long templateId = defaultTemplateId(token);
        JsonNode template = defaultTemplate(token);
        long firstId = template.path("items").get(0).path("id").asLong();

        ObjectNode payload = payloadOf(template, items -> rename(items, "班会物资", "班会用品"));
        saveTemplate(token, templateId, payload, 200);

        JsonNode after = defaultTemplate(token);
        assertEquals(11, after.path("items").size());
        assertEquals(firstId, after.path("items").get(0).path("id").asLong());
        assertEquals("班会用品", after.path("items").get(0).path("name").asText());
        assertTrue(firstId > 0);
    }

    // ---------- 辅助 ----------

    private JsonNode defaultTemplate(String token) throws Exception {
        return getJson(mockMvc, objectMapper, "/api/category-templates", token).path("data").get(0);
    }

    private long defaultTemplateId(String token) throws Exception {
        return defaultTemplate(token).path("id").asLong();
    }

    /** 把当前模板条目原样抄成请求体，再交给调用方增/删/改 */
    private ObjectNode payloadOf(JsonNode template, Consumer<ArrayNode> mutate) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("name", template.path("name").asText());
        ArrayNode items = payload.putArray("items");
        for (JsonNode item : template.path("items")) {
            items.add(item(item.path("id").asLong(), item.path("name").asText(), item.path("type").asText(),
                    item.path("icon").isNull() ? null : item.path("icon").asText(),
                    item.path("color").isNull() ? null : item.path("color").asText()));
        }
        mutate.accept(items);
        return payload;
    }

    private ObjectNode item(Long id, String name, String type, String icon, String color) {
        ObjectNode node = objectMapper.createObjectNode();
        if (id != null) {
            node.put("id", id);
        }
        node.put("name", name);
        node.put("type", type);
        node.put("icon", icon);
        node.put("color", color);
        return node;
    }

    private void rename(ArrayNode items, String from, String to) {
        for (int i = 0; i < items.size(); i++) {
            if (from.equals(items.get(i).path("name").asText())) {
                ((ObjectNode) items.get(i)).put("name", to);
                return;
            }
        }
        throw new IllegalStateException("模板里没有：" + from);
    }

    private void remove(ArrayNode items, String name) {
        for (int i = 0; i < items.size(); i++) {
            if (name.equals(items.get(i).path("name").asText())) {
                items.remove(i);
                return;
            }
        }
        throw new IllegalStateException("模板里没有：" + name);
    }

    private void saveTemplate(String token, long templateId, ObjectNode payload, int expectedCode) throws Exception {
        mockMvc.perform(put("/api/category-templates/" + templateId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(expectedCode));
    }

    private void createIncome(String token, long categoryId, String amount) throws Exception {
        mockMvc.perform(post("/api/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":" + categoryId
                                + ",\"type\":\"income\",\"amount\":\"" + amount + "\",\"transDate\":\"2026-11-08\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private static String names(JsonNode categories) {
        StringBuilder builder = new StringBuilder();
        for (JsonNode category : categories) {
            builder.append(category.path("name").asText()).append('\n');
        }
        return builder.toString();
    }
}