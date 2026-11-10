package com.bookkeep.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bookkeep.web.support.AuthHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static com.bookkeep.web.support.AuthHelper.json;
import static com.bookkeep.web.support.AuthHelper.nextUsername;
import static com.bookkeep.web.support.AuthHelper.registerAndLogin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 改名接口的细节测试（PUT /api/books/{id}）；增删查与回收站见 {@link BookManageApiTest}。
 * <p>这里专门钉住两件事：改名会让下一次登录看到新名字、但 shareToken 不变。
 * <p>账本只随登录响应返回，所以断言都走再登录一次。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BookApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("改名成功：登录响应里的账本名变了，已有的公示链接不失效")
    void renameIsReflectedInNextLogin() throws Exception {
        String username = nextUsername("book");
        String token = registerAndLogin(mockMvc, objectMapper, username);

        JsonNode before = loginData(username).path("books").get(0);
        long bookId = before.path("id").asLong();
        // 先真的分享一次（链接是点分享才生成的），再改名，验证链接不被改名作废
        String shareToken = AuthHelper.shareToken(mockMvc, objectMapper, username);
        assertEquals("班级账本", before.path("name").asText());

        mockMvc.perform(put("/api/books/" + bookId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"计科2301班费\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(bookId))
                .andExpect(jsonPath("$.data.name").value("计科2301班费"))
                .andExpect(jsonPath("$.data.current").value(true));

        JsonNode after = loginData(username).path("books").get(0);
        assertEquals("计科2301班费", after.path("name").asText());
        // 改名不影响公示链接：旧 token 仍能打开公示页
        assertEquals(200, json(mockMvc.perform(get("/api/public/report/" + shareToken)).andReturn(), objectMapper)
                .path("code").asInt(), "改名不能作废公示链接");
    }

    @Test
    @DisplayName("改别人的账本 → 404，且对方的账本名不变")
    void otherUsersBookIsNotFound() throws Exception {
        String tokenA = registerAndLogin(mockMvc, objectMapper, nextUsername("booka"));
        String usernameB = nextUsername("bookb");
        registerAndLogin(mockMvc, objectMapper, usernameB);
        long bookIdB = loginData(usernameB).path("books").get(0).path("id").asLong();

        mockMvc.perform(put("/api/books/" + bookIdB)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"被改掉了\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("账本不存在"));

        assertEquals("班级账本", loginData(usernameB).path("books").get(0).path("name").asText());
    }

    @Test
    @DisplayName("空名 / 全空格 / 超过 50 字 → 400")
    void invalidNamesAreRejected() throws Exception {
        String username = nextUsername("book");
        String token = registerAndLogin(mockMvc, objectMapper, username);
        long bookId = loginData(username).path("books").get(0).path("id").asLong();

        mockMvc.perform(put("/api/books/" + bookId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(put("/api/books/" + bookId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "账".repeat(51) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 名字没变：两次失败请求都不该留下痕迹
        assertEquals("班级账本", loginData(username).path("books").get(0).path("name").asText());
    }

    @Test
    @DisplayName("不带 token → 真实 HTTP 401（不是 200 + code=401）")
    void anonymousIsRejectedWith401() throws Exception {
        String username = nextUsername("book");
        registerAndLogin(mockMvc, objectMapper, username);
        long bookId = loginData(username).path("books").get(0).path("id").asLong();

        mockMvc.perform(put("/api/books/" + bookId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"匿名改名\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    /** 账本只随登录响应返回，所以每个断言都要再登录一次 */
    private JsonNode loginData(String username) throws Exception {
        return AuthHelper.loginData(mockMvc, objectMapper, username);
    }
}