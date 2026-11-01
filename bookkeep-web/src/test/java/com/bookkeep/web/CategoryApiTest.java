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
 * 分类接口集成测试（GET/POST/PUT/DELETE /api/categories）。
 * <p>重点验证三件事：注册预置的班级模板、越权隔离（A 的分类 B 碰不到）、删除时的引用检查。
 * <p>用真实 token（注册 + 登录），不用 {@code @WithMockUser}——原因见 AGENTS.md「Testing」。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CategoryApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("注册后预置 13 条班级分类；按 type 过滤能拿到 8 支出 / 5 收入")
    void templateCategoriesAreListed() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("cat"));

        JsonNode all = getJson(mockMvc, objectMapper, "/api/categories", token).path("data");
        assertEquals(13, all.size());

        JsonNode expenses = getJson(mockMvc, objectMapper, "/api/categories?type=expense", token).path("data");
        assertEquals(8, expenses.size());
        expenses.forEach(c -> assertEquals("expense", c.path("type").asText()));

        JsonNode incomes = getJson(mockMvc, objectMapper, "/api/categories?type=income", token).path("data");
        assertEquals(5, incomes.size());

        // 排序：sort_order 升序，模板里「班会物资」排第一
        assertEquals("班会物资", all.get(0).path("name").asText());
        assertEquals("收取班费", incomes.get(0).path("name").asText());
    }

    @Test
    @DisplayName("新增分类排在最后；改名生效；icon 不传时有兜底")
    void createAndUpdateCategory() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("cat"));

        MvcResult created = mockMvc.perform(post("/api/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"班服\",\"type\":\"expense\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("班服"))
                .andExpect(jsonPath("$.data.icon").value("other"))   // 兜底
                .andExpect(jsonPath("$.data.color").value("gray"))
                .andReturn();
        long id = json(created, objectMapper).path("data").path("id").asLong();

        JsonNode expenses = getJson(mockMvc, objectMapper, "/api/categories?type=expense", token).path("data");
        assertEquals(9, expenses.size());
        assertEquals("班服", expenses.get(expenses.size() - 1).path("name").asText()); // 排最后

        mockMvc.perform(put("/api/categories/" + id)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"班服定制\",\"type\":\"expense\",\"icon\":\"shirt\",\"color\":\"blue\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("班服定制"))
                .andExpect(jsonPath("$.data.icon").value("shirt"));
    }

    @Test
    @DisplayName("分类的收支类型不能改：改了会让历史流水方向失真 → 400")
    void categoryTypeCannotChange() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("cat"));
        long feeId = categoryId(mockMvc, objectMapper, token, "收取班费");

        mockMvc.perform(put("/api/categories/" + feeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"收取班费\",\"type\":\"expense\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("分类的收支类型不能修改"));
    }

    @Test
    @DisplayName("分类下还有流水时不允许删除（提示带条数），流水删掉后才能删")
    void deleteCategoryWithTransactionsRejected() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("cat"));
        long feeId = categoryId(mockMvc, objectMapper, token, "收取班费");

        MvcResult created = mockMvc.perform(post("/api/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":" + feeId + ",\"type\":\"income\",\"amount\":\"1900.00\","
                                + "\"transDate\":\"2026-09-05\",\"note\":\"全班 38 人 × 50\"}"))
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        long transactionId = json(created, objectMapper).path("data").path("id").asLong();

        mockMvc.perform(delete("/api/categories/" + feeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("该分类下还有 1 笔流水，先把流水改到别的分类再删"));

        // 把流水删掉后，分类就能删了
        mockMvc.perform(delete("/api/transactions/" + transactionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(delete("/api/categories/" + feeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 再删一次：已经是逻辑删除了，按「不存在」处理
        mockMvc.perform(delete("/api/categories/" + feeId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("越权隔离：A 的分类 B 看不到，也不能改/删（一律 404，不暴露存在性）")
    void categoriesAreIsolatedBetweenUsers() throws Exception {
        String tokenA = registerAndLogin(mockMvc, objectMapper, nextUsername("cat"));
        String tokenB = registerAndLogin(mockMvc, objectMapper, nextUsername("cat"));

        MvcResult created = mockMvc.perform(post("/api/categories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A的私有分类\",\"type\":\"expense\"}"))
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        long aCategoryId = json(created, objectMapper).path("data").path("id").asLong();

        JsonNode bCategories = getJson(mockMvc, objectMapper, "/api/categories", tokenB).path("data");
        assertEquals(13, bCategories.size()); // B 只有自己的模板分类
        assertFalse(bCategories.toString().contains("A的私有分类"));

        mockMvc.perform(put("/api/categories/" + aCategoryId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"抢过来\",\"type\":\"expense\"}"))
                .andExpect(jsonPath("$.code").value(404));

        mockMvc.perform(delete("/api/categories/" + aCategoryId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(jsonPath("$.code").value(404));

        // A 的分类没被动过
        JsonNode aCategories = getJson(mockMvc, objectMapper, "/api/categories", tokenA).path("data");
        assertTrue(aCategories.toString().contains("A的私有分类"));
    }

    @Test
    @DisplayName("非法 type 过滤 / 非法参数：400")
    void invalidTypeRejected() throws Exception {
        String token = registerAndLogin(mockMvc, objectMapper, nextUsername("cat"));

        mockMvc.perform(get("/api/categories?type=bogus")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("收支类型只能是 income 或 expense"));

        // 没有 token：真实 401（分类接口在 anyRequest().authenticated() 里）
        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }
}