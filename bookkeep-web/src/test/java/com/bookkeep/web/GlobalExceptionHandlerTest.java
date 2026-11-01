package com.bookkeep.web;

import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常处理集成测试：业务错误 HTTP 200 + body.code；只有授权失败用真实 403（第 7 天策略）。
 * <p>{@code addFilters = false}：这里只验证异常映射。第 6 天收紧 SecurityConfig 后，
 * 这些测试自造的 {@code /test/error/*} 端点没有 token，带上过滤器链会一律变成 401，
 * 测不到 {@code GlobalExceptionHandler} 本身。
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("业务异常：code / message 取自 BusinessException")
    void businessException() throws Exception {
        mockMvc.perform(get("/test/error/business"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("测试用业务异常"));
    }

    @Test
    @DisplayName("请求体校验失败：400 + 字段名")
    void validationFailed() throws Exception {
        mockMvc.perform(post("/test/error/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("name: 名字不能为空"));
    }

    @Test
    @DisplayName("方法参数校验失败：400")
    void constraintViolation() throws Exception {
        mockMvc.perform(get("/test/error/constraint").param("age", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("请求体不是合法 JSON：400 且不回显解析细节")
    void notReadable() throws Exception {
        mockMvc.perform(post("/test/error/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-a-json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求体格式错误"));
    }

    @Test
    @DisplayName("路径不存在：404 而不是 500")
    void notFound() throws Exception {
        mockMvc.perform(get("/test/no-such-endpoint"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("接口不存在"));
    }

    @Test
    @DisplayName("未预期异常：500 且不回显内部信息")
    void unknownException() throws Exception {
        mockMvc.perform(get("/test/error/unknown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value(ResultCode.ERROR.getMessage()));
    }

    @Test
    @DisplayName("授权失败：真实 HTTP 403 + code=403（不能被兜底吞成 200/500）")
    void accessDenied() throws Exception {
        mockMvc.perform(get("/test/error/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(ResultCode.FORBIDDEN.getMessage()));
    }

    /** 只为测试异常处理而存在的端点，仅注册在本测试的上下文中。 */
    @TestConfiguration
    static class ErrorEndpointsConfig {
        @Bean
        ErrorEndpoints errorEndpoints() {
            return new ErrorEndpoints();
        }
    }

    @RestController
    @Validated
    static class ErrorEndpoints {

        @GetMapping("/test/error/business")
        String business() {
            throw new BusinessException(ResultCode.FORBIDDEN, "测试用业务异常");
        }

        @GetMapping("/test/error/unknown")
        String unknown() {
            throw new IllegalStateException("boom");
        }

        /** 模拟 {@code @PreAuthorize} 拒绝：抛的正是 Spring Security 的 AccessDeniedException。 */
        @GetMapping("/test/error/denied")
        String denied() {
            throw new AccessDeniedException("测试用无权限");
        }

        @PostMapping("/test/error/validate")
        String validate(@Valid @RequestBody Payload payload) {
            return payload.name();
        }

        @GetMapping("/test/error/constraint")
        String constraint(@RequestParam @Min(value = 1, message = "必须大于 0") int age) {
            return String.valueOf(age);
        }

        record Payload(@NotBlank(message = "名字不能为空") String name) {
        }
    }
}
