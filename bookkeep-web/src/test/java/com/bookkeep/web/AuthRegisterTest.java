package com.bookkeep.web;

import com.bookkeep.model.dto.req.RegisterReq;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 注册接口集成测试（POST /api/auth/register）
 * <p>走完整上下文 + 真实 MySQL：MockMvc 调接口，JdbcTemplate 直查原始列做断言，
 * 这样能验证「密码确实被加密」「账本类型确实落库」「预置分类确实写进去了」这类表述层的假象。
 * <p>类上的 {@code @Transactional} 让每个用例结束后回滚，不污染开发库。
 * <p>当前 SecurityConfig 还是「全部放行」的临时版，所以这里不需要 token；
 * 第 6 天收紧后，本类的路由在 auth 白名单里，应当仍然不用改。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthRegisterTest {

    /** 用户名靠自增 + 时间戳保证各用例 / 各次运行之间不撞唯一索引（长度 16，符合 4–20 限制） */
    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("注册成功：用户落库（BCrypt）、账本建好、12 条模板分类、current_book_id 回写")
    void registerSuccess() throws Exception {
        String username = nextUsername();
        RegisterReq req = new RegisterReq(username, "abc123456", "小明", "ming@example.com", "我的账本", "personal");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())                       // 业务错误才走 200 + code，成功就是 200
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.nickname").value("小明"))
                .andExpect(jsonPath("$.data.email").value("ming@example.com"))
                .andExpect(jsonPath("$.data.currentBookId").isNumber())
                .andExpect(jsonPath("$.data.password").doesNotExist()); // 响应体绝不能带密码

        // 密码：库里必须是 BCrypt 哈希（$2a$10$ + 53 位），不是明文
        String rawPassword = jdbcTemplate.queryForObject(
                "SELECT password FROM user WHERE username = ?", String.class, username);
        assertTrue(rawPassword.matches("^\\$2[aby]\\$\\d{2}\\$.{53}$"), "密码应存 BCrypt 哈希，实际：" + rawPassword);
        assertNotEquals("abc123456", rawPassword);

        // 账本：名字 / 类型 / 归属都要对
        Long bookId = jdbcTemplate.queryForObject(
                "SELECT current_book_id FROM user WHERE username = ?", Long.class, username);
        assertEquals("我的账本", jdbcTemplate.queryForObject(
                "SELECT name FROM book WHERE id = ?", String.class, bookId));
        assertEquals("personal", jdbcTemplate.queryForObject(
                "SELECT type FROM book WHERE id = ?", String.class, bookId));
        assertEquals(username, jdbcTemplate.queryForObject(
                "SELECT u.username FROM user u JOIN book b ON b.user_id = u.id WHERE b.id = ?", String.class, bookId));

        // 预置分类：personal 模板 12 条（8 支出 + 4 收入）
        assertEquals(12, countCategories(bookId));
    }

    @Test
    @DisplayName("商家账本走商家模板：12 条分类，9 支出 / 3 收入")
    void registerMerchantBookUsesMerchantTemplate() throws Exception {
        String username = nextUsername();
        RegisterReq req = new RegisterReq(username, "abc123456", null, null, "小店账本", "MERCHANT");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.nickname").value(username)); // 昵称留空时默认用用户名

        // 大小写 / 空格应被归一化成小写再落库
        Long bookId = jdbcTemplate.queryForObject(
                "SELECT current_book_id FROM user WHERE username = ?", Long.class, username);
        assertEquals("merchant", jdbcTemplate.queryForObject(
                "SELECT type FROM book WHERE id = ?", String.class, bookId));

        assertEquals(12, countCategories(bookId));
        assertEquals(9, countCategories(bookId, "expense"));
        assertEquals(3, countCategories(bookId, "income"));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM category WHERE book_id = ? AND name = ?", Integer.class, bookId, "采购"));
    }

    @Test
    @DisplayName("用户名重复：400 + 友好提示，且不会产生第二条用户")
    void duplicateUsernameRejected() throws Exception {
        String username = nextUsername();
        register(username, "personal").andExpect(jsonPath("$.code").value(200));

        register(username, "personal")
                .andExpect(status().isOk())                       // 业务错误仍是 HTTP 200（PLAN 第 7 天的策略）
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("用户名已存在"));

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user WHERE username = ?", Integer.class, username));
    }

    @Test
    @DisplayName("已注销（逻辑删除）的用户名可以重新注册：墓碑行在唯一索引里是 NULL，不占名字")
    void softDeletedUsernameCanBeReused() throws Exception {
        String username = nextUsername();
        // 造一条已注销的历史账号：deleted=1 → 生成列 username_active 为 NULL
        jdbcTemplate.update("INSERT INTO user (username, password, deleted) VALUES (?, ?, 1)",
                username, "$2a$10$abcdefghijklmnopqrstuv");

        register(username, "personal")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value(username));

        // 同名两行：一行墓碑、一行新账号，互不冲突
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user WHERE username = ? AND deleted = 1", Integer.class, username));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user WHERE username = ? AND deleted = 0", Integer.class, username));

        // 新账号是另一个人：有自己的 current_book_id 和 12 条模板分类，不复用墓碑的数据
        Long newBookId = jdbcTemplate.queryForObject(
                "SELECT current_book_id FROM user WHERE username = ? AND deleted = 0", Long.class, username);
        assertEquals(12, countCategories(newBookId));
    }

    @Test
    @DisplayName("用户名区分大小写：Reg… 和 reg… 是两个账号（utf8mb4_0900_as_cs）")
    void usernameIsCaseSensitive() throws Exception {
        String lower = nextUsername();                                        // reg + 时间戳
        String upper = Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
        assertNotEquals(lower, upper);

        register(upper, "personal").andExpect(jsonPath("$.code").value(200));
        register(lower, "personal").andExpect(jsonPath("$.code").value(200));

        // 只按 username 查会查出两行（正好验证 idx_user_username 不唯一），
        // 所以业务查询必须带 deleted = 0 —— 现有 MP 查询都会自动拼
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user WHERE username IN (?, ?) AND deleted = 0",
                Integer.class, lower, upper));
    }

    @Test
    @DisplayName("非法账本类型：400 且一个用户都不落库")
    void invalidBookTypeRejected() throws Exception {
        String username = nextUsername();
        RegisterReq req = new RegisterReq(username, "abc123456", null, null, "奇怪账本", "biz");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("账本类型只能是 personal / merchant"));

        // 类型校验放在最前面，就是为了不留「有用户没账本」的半成品
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user WHERE username = ?", Integer.class, username));
    }

    @Test
    @DisplayName("参数校验失败：密码过短 / 用户名格式不对 → 400，且不落库")
    void invalidParamsRejected() throws Exception {
        String username = nextUsername();

        // 密码过短：由 RegisterReq 上的 @Size 拦下，经 GlobalExceptionHandler 转成 "password: xxx"
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterReq(username, "123", null, null, "账本", "personal"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("密码长度")));

        // 用户名含非法字符
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterReq("bad name!", "abc123456", null, null, "账本", "personal"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("用户名只能包含")));

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user WHERE username = ?", Integer.class, username));
    }

    // ---------- 辅助方法 ----------

    private String nextUsername() {
        return "reg" + SEQ.incrementAndGet();
    }

    /** 发一次注册请求（不含断言，交给调用方继续链式断言） */
    private org.springframework.test.web.servlet.ResultActions register(String username, String bookType) throws Exception {
        RegisterReq req = new RegisterReq(username, "abc123456", null, null, "测试账本", bookType);
        return mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)));
    }

    private int countCategories(Long bookId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM category WHERE book_id = ? AND deleted = 0", Integer.class, bookId);
    }

    private int countCategories(Long bookId, String type) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM category WHERE book_id = ? AND type = ? AND deleted = 0",
                Integer.class, bookId, type);
    }
}
