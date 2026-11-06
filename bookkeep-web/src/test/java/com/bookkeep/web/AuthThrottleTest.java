package com.bookkeep.web;

import com.bookkeep.service.support.AuthThrottle;
import com.bookkeep.web.support.AuthHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static com.bookkeep.web.support.AuthHelper.PASSWORD;
import static com.bookkeep.web.support.AuthHelper.nextUsername;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 登录/注册限流（PLAN §9 第 1 条，公网部署前置条件）。
 * <p>默认配置对测试是宽松的，这里用独立属性把阈值调小，单独一个 Spring 上下文，不影响其它测试。
 * <p>限流器是进程内单例，{@code @Transactional} 回滚不了它，所以每个用例前 clear。
 */
@SpringBootTest(properties = {
        "app.security.login-max-failures=3",
        "app.security.login-ip-per-minute=1000",
        "app.security.register-per-hour=2"
})
@AutoConfigureMockMvc
@Transactional
class AuthThrottleTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AuthThrottle authThrottle;

    @BeforeEach
    void setUp() {
        authThrottle.clear();
    }

    @Test
    @DisplayName("同一账号连续输错 3 次后：即使密码正确也先拒绝，提示 5 分钟后再试")
    void tooManyFailedLoginsLocksAccount() throws Exception {
        String username = nextUsername("thr");
        AuthHelper.register(mockMvc, objectMapper, username);
        String email = AuthHelper.emailOf(username);

        for (int i = 0; i < 3; i++) {
            login(email, "wrong-password")
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("邮箱或密码错误"));
        }

        login(email, PASSWORD)
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("密码错误次数过多，请 5 分钟后再试"));
    }

    @Test
    @DisplayName("登录成功会清零失败计数：输错两次后成功，再错一次仍按新计数算")
    void successfulLoginResetsCounter() throws Exception {
        String username = nextUsername("thr");
        AuthHelper.register(mockMvc, objectMapper, username);
        String email = AuthHelper.emailOf(username);

        login(email, "wrong-password").andExpect(jsonPath("$.code").value(400));
        login(email, "wrong-password").andExpect(jsonPath("$.code").value(400));
        login(email, PASSWORD).andExpect(jsonPath("$.code").value(200));

        // 如果没清零，这里第 3 次失败就会锁；现在应该还是普通错误
        login(email, "wrong-password")
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("邮箱或密码错误"));
    }

    @Test
    @DisplayName("同一 IP 每小时注册次数用尽后：再注册返回「注册过于频繁」")
    void registerIsLimitedPerIp() throws Exception {
        AuthHelper.register(mockMvc, objectMapper, nextUsername("thr"));
        AuthHelper.register(mockMvc, objectMapper, nextUsername("thr"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new com.bookkeep.model.dto.req.RegisterReq(
                                        nextUsername("thr"), PASSWORD, "over@example.com", null))))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("注册过于频繁，请稍后再试"));
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password)
            throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }
}