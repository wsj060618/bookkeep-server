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
import org.springframework.jdbc.core.JdbcTemplate;
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
 * <p>重点验证这几件事：注册时即落一套真实默认模板（11 条，无「其他」）、从零新建模板/快照模板、
 * 修改模板时整体替换条目、删除模板不留孤儿条目、有流水时禁止换模板、模板跟账号走（别人碰不到）。
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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String BUILTIN_ITEMS = "$.data[0].items";

    /** 两支出 + 一收入的小模板，省得每个用例都拼长 JSON */
    private static final String TWO_ITEMS =
            "{\"name\":\"%s\",\"items\":["
                    + "{\"name\":\"打印费\",\"type\":\"expense\",\"icon\":\"print\",\"color\":\"cyan\"},"
                    + "{\"name\":\"聚餐\",\"type\":\"expense\",\"icon\":\"dining\",\"color\":\"red\"},"
                    + "{\"name\":\"班费\",\"type\":\"income\",\"icon\":\"fee\",\"color\":\"green\"}]}";

    @Test
    @DisplayName("注册即落一套真实默认模板：11 条、可编辑（不再只有 id=0 的只读投影）")
    void defaultTemplateIsMaterializedAtRegistration() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("tpl"));

        mockMvc.perform(get("/api/category-templates")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(1))
                // 是一条真实的模板行（id > 0），不是 id=0 的常量投影
                .andExpect(jsonPath("$.data[0].id").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.data[0].name").value("班级班费"))
                .andExpect(jsonPath("$.data[0].builtin").value(false))
                .andExpect(jsonPath(BUILTIN_ITEMS + ".length()").value(11))
                .andExpect(jsonPath(BUILTIN_ITEMS + "[0].name").value("班会物资"))
                .andExpect(jsonPath(BUILTIN_ITEMS + "[0].sortOrder").value(1));

        // 「其他」是记一笔里的虚拟入口，不是预置分类
        assertFalse(getJson(mockMvc, objectMapper, "/api/category-templates", token)
                .path("data").get(0).path("items").toString().contains("其他"));

        // 默认模板就是普通模板：能直接改名（不用先「存为模板」）
        long templateId = getJson(mockMvc, objectMapper, "/api/category-templates", token)
                .path("data").get(0).path("id").asLong();
        mockMvc.perform(put("/api/category-templates/" + templateId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"我们班的分类\"}"))
                .andExpect(jsonPath("$.code").value(200));
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

        // 删模板必须连条目一起删：留下 template_id 指向已删模板的孤儿行是数据垃圾，
        // 也会让人误以为「条目表里一堆重复」
        Integer orphanItems = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM category_template_item WHERE template_id = ?",
                Integer.class, templateId);
        assertEquals(0, orphanItems);

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

    @Test
    @DisplayName("模板导出带图标/颜色/排序；再导回来是新模板（重名自动加后缀），账本分类不受影响")
    void exportThenImportRoundTrip() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("tpl"));
        long templateId = getJson(mockMvc, objectMapper, "/api/category-templates", token)
                .path("data").get(0).path("id").asLong();

        JsonNode doc = getJson(mockMvc, objectMapper, "/api/category-templates/" + templateId + "/export", token)
                .path("data");
        assertEquals(1, doc.path("version").asInt());
        assertEquals("班级班费", doc.path("name").asText());
        assertEquals(11, doc.path("items").size());
        // 颜色/图标就是导出的重点：只带名字的话换个账号导入会全变成灰色
        assertEquals("班会物资", doc.path("items").get(0).path("name").asText());
        assertEquals("supplies", doc.path("items").get(0).path("icon").asText());
        assertEquals("orange", doc.path("items").get(0).path("color").asText());
        assertEquals(1, doc.path("items").get(0).path("sortOrder").asInt());

        JsonNode first = importDoc(token, doc.toString()).path("data");
        assertEquals("班级班费（导入）", first.path("name").asText());
        assertTrue(first.path("id").asLong() > 0, "导入的是新模板，不是覆盖原来那套");
        assertEquals(11, first.path("items").size());
        assertEquals("orange", first.path("items").get(0).path("color").asText());
        assertEquals("cyan", first.path("items").get(3).path("color").asText());

        // 再导一次：前缀一样也要能导进去
        JsonNode second = importDoc(token, doc.toString()).path("data");
        assertEquals("班级班费（导入 2）", second.path("name").asText());

        assertEquals(3, getJson(mockMvc, objectMapper, "/api/category-templates", token).path("data").size());
        // 导入是纯「加一套模板」，当前账本的分类一个不动
        assertEquals(11, getJson(mockMvc, objectMapper, "/api/categories", token).path("data").size());
    }

    @Test
    @DisplayName("导入模板的兜底：方向认中英文、缺图标颜色给默认值、脏条目丢掉、版本太新拒绝")
    void importTemplateToleratesDirtyDoc() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("tpl"));

        String dirty = "{\"version\":1,\"name\":\"手写的\",\"items\":["
                + "{\"name\":\" 打印费 \",\"type\":\"支出\"},"
                + "{\"name\":\"\",\"type\":\"expense\"},"
                + "{\"name\":\"班费\",\"type\":\"income\",\"icon\":\"fee\",\"color\":\"green\"},"
                + "{\"name\":\"打印费\",\"type\":\"expense\"}]}";
        JsonNode created = importDoc(token, dirty).path("data");
        assertEquals("手写的", created.path("name").asText());
        // 空名丢掉、同方向同名只留第一条
        assertEquals(2, created.path("items").size(), created.toString());
        assertEquals("打印费", created.path("items").get(0).path("name").asText());
        assertEquals("expense", created.path("items").get(0).path("type").asText());
        assertEquals("other", created.path("items").get(0).path("icon").asText());
        assertEquals("gray", created.path("items").get(0).path("color").asText());
        assertEquals("income", created.path("items").get(1).path("type").asText());
        assertEquals("green", created.path("items").get(1).path("color").asText());

        // 版本比系统新：直接拒绝，别猜字段
        assertTrue(importDoc(token, "{\"version\":99,\"name\":\"未来\",\"items\":["
                + "{\"name\":\"打印费\",\"type\":\"expense\"}]}")
                .path("message").asText().contains("版本"));
        // 一条可用分类都没有
        assertTrue(importDoc(token, "{\"version\":1,\"name\":\"空\",\"items\":[]}")
                .path("message").asText().contains("没有可导入的分类"));
    }

    private JsonNode importDoc(String token, String body) throws Exception {
        return json(mockMvc.perform(post("/api/category-templates/import")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn(), objectMapper);
    }
}