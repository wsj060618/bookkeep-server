package com.bookkeep.web.controller;

import com.bookkeep.common.result.Result;
import com.bookkeep.service.AuthService;
import com.bookkeep.service.PasswordResetService;
import com.bookkeep.model.dto.req.ForgotPasswordReq;
import com.bookkeep.model.dto.req.LoginReq;
import com.bookkeep.model.dto.req.RegisterReq;
import com.bookkeep.model.dto.req.ResetPasswordReq;
import com.bookkeep.model.dto.resp.LoginResp;
import com.bookkeep.model.dto.resp.UserResp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口
 * <p>日志由 {@code LogAspect} 统一记录（password 会被脱敏），这里不再重复打日志；
 * 异常交给 {@code GlobalExceptionHandler} 转成统一的 {@code {code, message, data}}。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;

    /**
     * 注册并创建第一本账本
     * <p>成功返回用户信息（含 currentBookId），不含 token；前端注册成功后调 {@code /api/auth/login} 拿 token。
     */
    @PostMapping("/register")
    public Result<UserResp> register(@Valid @RequestBody RegisterReq req, HttpServletRequest request) {
        return Result.success(authService.register(req, clientIp(request)));
    }

    /**
     * 登录并签发 token
     * <p>响应 {@code {token, user, books}}：前端拿到后存 token，后续请求带 {@code Authorization: Bearer ...}。
     * 用户名不存在和密码错误都是 {@code code=400 + "用户名或密码错误"}，不区分两者。
     */
    @PostMapping("/login")
    public Result<LoginResp> login(@Valid @RequestBody LoginReq req, HttpServletRequest request) {
        return Result.success(authService.login(req, clientIp(request)));
    }

    /**
     * 当前用户信息（含 currentBookId）
     * <p>鉴权由 {@code SecurityConfig} 完成（这个路径<strong>不在</strong>放行名单里），
     * 这里不接收任何参数、也不解析 token：userId 从 SecurityContext 取。
     * <p>前端刷新页面后用它恢复登录态；账本列表由 P4 的 {@code GET /api/books} 提供，此接口只返回用户。
     */
    @GetMapping("/me")
    public Result<UserResp> me() {
        return Result.success(authService.getCurrentUser());
    }

    /**
     * 申请重置密码（匿名）
     * <p><strong>永远返回同一句提示</strong>：账号信息是否匹配、是否限流、SMTP 是否可用都不对外暴露，
     * 否则这个接口就成了「这个账号/邮箱存不存在」的探测器。
     */
    @PostMapping("/forgot-password")
    public Result<Void> forgotPassword(@Valid @RequestBody ForgotPasswordReq req,
                                       HttpServletRequest request) {
        passwordResetService.requestReset(req, clientIp(request));
        return Result.success();
    }

    /**
     * 用邮件里的一次性 token 重置密码（匿名）
     * <p>成功后该用户所有旧 token 立即失效（`token_version + 1`）。
     */
    @PostMapping("/reset-password")
    public Result<Void> resetPassword(@Valid @RequestBody ResetPasswordReq req) {
        passwordResetService.resetPassword(req);
        return Result.success();
    }

    /** Nginx 反代后 getRemoteAddr() 永远是 127.0.0.1，真实 IP 在 X-Real-IP（PLAN §8 第 21 条） */
    static String clientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        return StringUtils.hasText(realIp) ? realIp.trim() : request.getRemoteAddr();
    }
}
