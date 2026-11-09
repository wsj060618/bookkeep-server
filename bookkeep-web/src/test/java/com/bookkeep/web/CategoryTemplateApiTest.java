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

import static com.bookkeep.web.support.AuthHelper.categoryId;
import static com.bookkeep.web.support.AuthHelper.getJson;
import static com.bookkeep.web.support.AuthHelper.json;
import static com.bookkeep.web.support.AuthHelper.nextUsername;
import static com.bookkeep.web.support.AuthHelper.registerAndLogin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分类模板接口集成测试（/api/category-templates）。
 * <p>重点验证五件事：内置模板 id=0 不入库（11 条，无「其他」）、从零新建模板/快照模板、
 * 修改模板时整体替换条目、有流水时禁止换模板、模板跟账号走（别人碰不到）。
 * 用真实 token，不用 {@code @WithMockUser}（原因见 AGENTS.md）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CategoryTemplateApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String BUILTIN_ITEMS = "$.data[0].items";

    /** 两支出 + 一收入的小模板，省得每个用例都拼长 JSON */
    private static final String TWO_ITEMS =
            "{\"name\":\"%s\",\"items\":["
                    + "{\"name\":\"打印费\",\"type\":\"expense\",\"icon\":\"print\",\"color\":\"cyan\"},"
                    + "{\"name\":\"聚餐\",\"type\":\"expense\",\"icon\":\"dining\",\"color\":\"red\"},"
                    + "{\"name\":\"班费\",\"type\":\"income\",\"icon\":\"fee\",\"color\":\"green\"}]}";

    @Test
    @DisplayName("模板库默认只有内置「班级班费」：id=0、11 条、排第一、没有「其他」")
    void builtinTemplateIsListed() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("tpl"));

        mockMvc.perform(get("/api/category-templates")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(0))
                .andExpect(jsonPath("$.data[0].name").value("班级班费"))
                .andExpect(jsonPath("$.data[0].builtin").value(true))
                .andExpect(jsonPath(BUILTIN_ITEMS + ".length()").value(11))
                .andExpect(jsonPath(BUILTIN_ITEMS + "[0].name").value("班会物资"))
                .andExpect(jsonPath(BUILTIN_ITEMS + "[0].sortOrder").value(1));

        // 「其他」是记一笔里的虚拟入口，不是预置分类
        assertFalse(getJson(mockMvc, objectMapper, "/api/category-templates", token)
                .path("data").get(0).path("items").toString().contains("其他"));
    }

    @Test
    @DisplayName("从零新建模板：条目按传入顺序编号，支出/收入名字相同互不冲突")
    void createTemplateWithItems() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("tpl"));

        mockMvc.perform(post("/api/category-templates")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TWO_ITEMS.formatted("自建模板")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.builtin").value(false))
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[0].name").value("打印费"))
                .andExpect(jsonPath("$.data.items[0].sortOrder").value(1))
                .andExpect(jsonPath("$.data.items[1].sortOrder").value(2))
                .andExpect(jsonPath("$.data.items[2].type").value("income"))
                .andExpect(jsonPath("$.data.items[2].sortOrder").value(3));

        // 支出、收入各一条同名：允许
        mockMvc.perform(post("/api/category-templates")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"同名模板\",\"items\":["
                                + "{\"name\":\"其他\",\"type\":\"expense\"},"
                                + "{\"name\":\"其他\",\"type\":\"income\"}]}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items.length()").value(2));
    }

    @Test
    @DisplayName("新建模板的校验：条目为空 / 同方向重名 / 方向非法，一律 400")
    void createTemplateValidatesItems() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("tpl"));

        mockMvc.perform(post("/api/category-templates")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"空模板\",\"items\":[]}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("模板至少要有 1 个分类"));

        mockMvc.perform(post("/api/category-templates")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"重名模板\",\"items\":["
                                + "{\"name\":\"打印费\",\"type\":\"expense\"},"
                                + "{\"name\":\"打印费\",\"type\":\"expense\"}]}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("同一方向下分类名不能重复：「打印费」"));

        mockMvc.perform(post("/api/category-templates")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"怪模板\",\"items\":[{\"name\":\"打印费\",\"type\":\"transfer\"}]}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("分类方向只能是收入或支出"));
    }

    @Test
    @DisplayName("修改模板：只传 name = 改名不动条目；带 items = 条目整体替换")
    void updateTemplateReplacesItems() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("tpl"));

        MvcResult created = mockMvc.perform(post("/api/category-templates")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TWO_ITEMS.formatted("旧名字")))
                .andReturn();
        long templateId = json(created, objectMapper).path("data").path("id").asLong();

        // 只改名：条目还是 3 条
        mockMvc.perform(put("/api/category-templates/" + templateId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新名字\"}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("新名字"))
                .andExpect(jsonPath("$.data.items.length()").value(3));

        // 带 items：整体替换成 1 条
        mockMvc.perform(put("/api/category-templates/" + templateId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"新名字\",\"items\":["
                                + "{\"name\":\"唯一分类\",\"type\":\"income\",\"icon\":\"fee\",\"color\":\"green\"}]}"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("唯一分类"))
                .andExpect(jsonPath("$.data.items[0].sortOrder").value(1));

        JsonNode listed = getJson(mockMvc, objectMapper, "/api/category-templates", token).path("data").get(1);
        assertEquals(1, listed.path("items").size());
        assertEquals("唯一分类", listed.path("items").get(0).path("name").asText());
    }

    @Test
    @DisplayName("把当前账本存成模板 → 出现在模板库；删掉现有分类后应用它 → 分类整套恢复")
    void snapshotThenApplyRestoresCategories() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("tpl"));

        MvcResult created = mockMvc.perform(post("/api/category-templates/from-book")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"我的模板\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.builtin").value(false))
                .andExpect(jsonPath("$.data.items.length()").value(11))
                .andReturn();
        long templateId = json(created, objectMapper).path("data").path("id").asLong();

        // 删掉两条分类（没有流水，可以删），证明「应用模板」是整套替换而不是追加
        long diningId = categoryId(mockMvc, objectMapper, token, "聚餐");
        long venueId = categoryId(mockMvc, objectMapper, token, "场地");
        mockMvc.perform(delete("/api/categories/" + diningId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(delete("/api/categories/" + venueId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(200));
        assertEquals(9, getJson(mockMvc, objectMapper, "/api/categories", token).path("data").size());

        mockMvc.perform(post("/api/category-templates/" + templateId + "/apply")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        JsonNode categories = getJson(mockMvc, objectMapper, "/api/categories", token).path("data");
        assertEquals(11, categories.size());                 // 不是 9 + 11
        assertEquals("聚餐", categories.get(2).path("name").asText());
        assertEquals("场地", categories.get(4).path("name").asText());

        // 应用后账本记住「基于这套模板」
        JsonNode books = getJson(mockMvc, objectMapper, "/api/books", token).path("data");
        assertEquals(templateId, books.get(0).path("categoryTemplateId").asLong());
    }

    @Test
    @DisplayName("账本已有流水 → 不能换模板（400 且提示笔数）；内置模板同样受限")
    void applyRejectedWhenBookHasTransactions() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("tpl"));
        long feeId = categoryId(mockMvc, objectMapper, token, "收取班费");

        mockMvc.perform(post("/api/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":" + feeId + ",\"type\":\"income\",\"amount\":\"50.00\","
                                + "\"transDate\":\"2026-09-05\"}"))
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(post("/api/category-templates/0/apply")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        "当前账本已有 1 笔流水，不能再换模板；请新建一个账本或先清空流水"));

        // 分类一条没少：拒绝时不能先删后报错
        assertEquals(11, getJson(mockMvc, objectMapper, "/api/categories", token).path("data").size());
    }

    @Test
    @DisplayName("内置模板只能应用：改名/删除一律 400")
    void builtinTemplateIsReadOnly() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("tpl"));

        mockMvc.perform(put("/api/category-templates/0")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"改名试试\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("内置模板不能修改或删除，只能应用"));

        mockMvc.perform(delete("/api/category-templates/0")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("模板跟账号走：B 看不到 A 的模板，也不能改名/删除/应用（一律 404）")
    void templatesAreIsolatedBetweenUsers() throws Exception {
        String tokenA = registerAndLogin(mockMvc, objectMapper, nextUsername("tpl"));
        String tokenB = registerAndLogin(mockMvc, objectMapper, nextUsername("tpl"));

        MvcResult created = mockMvc.perform(post("/api/category-templates")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TWO_ITEMS.formatted("A的模板")))
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        long aTemplateId = json(created, objectMapper).path("data").path("id").asLong();

        JsonNode bTemplates = getJson(mockMvc, objectMapper, "/api/category-templates", tokenB).path("data");
        assertEquals(1, bTemplates.size());                                  // B 只有内置
        assertFalse(bTemplates.toString().contains("A的模板"));

        mockMvc.perform(put("/api/category-templates/" + aTemplateId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"抢过来\"}"))
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(post("/api/category-templates/" + aTemplateId + "/apply")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(delete("/api/category-templates/" + aTemplateId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(jsonPath("$.code").value(404));

        assertTrue(getJson(mockMvc, objectMapper, "/api/category-templates", tokenA)
                .path("data").toString().contains("A的模板"));
    }

    @Test
    @DisplayName("模板名同一账号内唯一；删除模板后账本回落到内置（categoryTemplateId 变回 null）")
    void templateNameIsUniqueAndDeleteResetsBookReference() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("tpl"));

        MvcResult created = mockMvc.perform(post("/api/category-templates")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TWO_ITEMS.formatted("学期模板")))
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        long templateId = json(created, objectMapper).path("data").path("id").asLong();

        mockMvc.perform(post("/api/category-templates")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TWO_ITEMS.formatted("学期模板")))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("已有同名模板「学期模板」"));

        mockMvc.perform(post("/api/category-templates/" + templateId + "/apply")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(delete("/api/category-templates/" + templateId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(200));

        JsonNode books = getJson(mockMvc, objectMapper, "/api/books", token).path("data");
        assertTrue(books.get(0).path("categoryTemplateId").isNull());

        // 模板没了，但账本里的分类还在（应用只是一次性复制，不是引用）：就是刚才那 3 条
        assertEquals(3, getJson(mockMvc, objectMapper, "/api/categories", token).path("data").size());
    }

    @Test
    @DisplayName("存为模板要求当前账本有分类：把分类全删光时 400")
    void snapshotRequiresAtLeastOneCategory() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("tpl"));

        JsonNode categories = getJson(mockMvc, objectMapper, "/api/categories", token).path("data");
        for (JsonNode category : categories) {
            mockMvc.perform(delete("/api/categories/" + category.path("id").asLong())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(jsonPath("$.code").value(200));
        }

        mockMvc.perform(post("/api/category-templates/from-book")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"空模板\"}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("当前账本还没有分类，先建一个分类再存为模板"));
    }

    @Test
    @DisplayName("新建账本时选模板：分类直接来自该模板，categoryTemplateId 回写，并自动切为当前")
    void createBookWithTemplateSeedsItsCategories() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("tpl"));

        long templateId = json(
                mockMvc.perform(post("/api/category-templates")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(TWO_ITEMS.formatted("精简模板")))
                        .andReturn(), objectMapper)
                .path("data").path("id").asLong();

        long newBookId = json(
                mockMvc.perform(post("/api/books")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"第二本\",\"templateId\":" + templateId + "}"))
                        .andReturn(), objectMapper)
                .path("data").path("id").asLong();

        // 新建即切为当前，所以这里查到的就是新账本的分类：直接就是模板那 3 条，不是内置 11 条
        JsonNode seeded = getJson(mockMvc, objectMapper, "/api/categories", token).path("data");
        assertEquals(3, seeded.size());
        assertEquals("打印费", seeded.get(0).path("name").asText());
        assertEquals("聚餐", seeded.get(1).path("name").asText());
        assertEquals("班费", seeded.get(2).path("name").asText());

        JsonNode books = getJson(mockMvc, objectMapper, "/api/books", token).path("data");
        JsonNode created = null;
        for (JsonNode book : books) {
            if (book.path("id").asLong() == newBookId) {
                created = book;
            }
        }
        assertTrue(created != null, "新建的账本应出现在列表里");
        assertEquals(templateId, created.path("categoryTemplateId").asLong());
        assertTrue(created.path("current").asBoolean(), "新建账本应自动切为当前");
    }

    @Test
    @DisplayName("新建账本不传模板 = 内置「班级班费」11 条，categoryTemplateId 为 null")
    void createBookWithoutTemplateUsesBuiltin() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("tpl"));

        JsonNode created = json(
                mockMvc.perform(post("/api/books")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"默认账本\"}"))
                        .andReturn(), objectMapper)
                .path("data");

        assertTrue(created.path("categoryTemplateId").isNull());
        assertEquals(11, getJson(mockMvc, objectMapper, "/api/categories", token).path("data").size());
    }

    @Test
    @DisplayName("用别人的模板建账本 → 404，且账本没建出来（不能留半本）")
    void createBookWithOthersTemplateIsRejected() throws Exception {
        String tokenA = registerAndLogin(mockMvc, objectMapper, nextUsername("tpl"));
        String tokenB = registerAndLogin(mockMvc, objectMapper, nextUsername("tpl"));

        long aTemplateId = json(
                mockMvc.perform(post("/api/category-templates")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(TWO_ITEMS.formatted("A的模板")))
                        .andReturn(), objectMapper)
                .path("data").path("id").asLong();

        mockMvc.perform(post("/api/books")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"偷模板\",\"templateId\":" + aTemplateId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("模板不存在"));

        assertEquals(1, getJson(mockMvc, objectMapper, "/api/books", tokenB).path("data").size());
    }
}