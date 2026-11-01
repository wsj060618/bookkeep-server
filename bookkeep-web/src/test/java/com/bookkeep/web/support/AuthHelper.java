package com.bookkeep.web.support;

import com.bookkeep.model.dto.req.RegisterReq;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 集成测试的公共动作：注册 → 登录拿真 token → 带 token 发请求。
 * <p>放在 {@code com.bookkeep.web.support} 而不是 {@code com.bookkeep.web} 根包，
 * 是为了让「按命名约定找测试类」的人一眼看出它不是测试（Surefire 只跑 {@code *Test}）。
 * <p>为什么不用 {@code @WithMockUser}：见 AGENTS.md「Testing」一节——它的 principal 是 {@code User} 对象，
 * 而 {@code SecurityUtil} 只认 token 里的 {@code Long} userId。
 */
public final class AuthHelper {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    private static final String PASSWORD = "abc123456";

    private AuthHelper() {
    }

    /** 生成一个不撞唯一索引的用户名（长度 16，符合 4–20 位限制）。 */
    public static String nextUsername(String prefix) {
        return prefix + SEQ.incrementAndGet();
    }

    /** 注册 + 登录，返回可直接放进 {@code Authorization} 头的 token。 */
    public static String registerAndLogin(MockMvc mockMvc, ObjectMapper objectMapper, String username)
            throws Exception {
        register(mockMvc, objectMapper, username);
        return login(mockMvc, objectMapper, username);
    }

    public static void register(MockMvc mockMvc, ObjectMapper objectMapper, String username) throws Exception {
        RegisterReq req = new RegisterReq(username, PASSWORD, null, null, null);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    public static String login(MockMvc mockMvc, ObjectMapper objectMapper, String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        String token = json(result, objectMapper).path("data").path("token").asText();
        assertNotNull(token);
        return token;
    }

    /** 带上 Bearer token 的 GET，返回解析后的 JSON（按 UTF-8 解码，否则中文提示乱码）。 */
    public static JsonNode getJson(MockMvc mockMvc, ObjectMapper objectMapper, String url, String token)
            throws Exception {
        return json(mockMvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn(), objectMapper);
    }

    public static JsonNode json(MvcResult result, ObjectMapper objectMapper) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 取某个分类的 id（按账本模板里的分类名找），找不到就 fail。 */
    public static long categoryId(MockMvc mockMvc, ObjectMapper objectMapper, String token, String name)
            throws Exception {
        JsonNode categories = getJson(mockMvc, objectMapper, "/api/categories", token).path("data");
        for (JsonNode category : categories) {
            if (name.equals(category.path("name").asText())) {
                return category.path("id").asLong();
            }
        }
        throw new IllegalStateException("分类不存在：" + name);
    }
}