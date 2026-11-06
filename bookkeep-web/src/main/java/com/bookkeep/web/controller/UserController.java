package com.bookkeep.web.controller;

import com.bookkeep.common.result.Result;
import com.bookkeep.model.dto.req.AccountDeleteReq;
import com.bookkeep.model.dto.req.PasswordUpdateReq;
import com.bookkeep.model.dto.req.UserUpdateReq;
import com.bookkeep.model.dto.resp.UserResp;
import com.bookkeep.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户自助管理（PLAN §5.3）：改资料 / 改密码 / 注销账户。
 * <p>路径统一是 {@code /me}：userId 只从 token 取（SecurityUtil），不接受客户端传。
 */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** 改昵称 / 邮箱（空串 = 清空） */
    @PutMapping("/me")
    public Result<UserResp> updateMe(@Valid @RequestBody UserUpdateReq req) {
        return Result.success(userService.updateProfile(req));
    }

    /** 改密码：要旧密码；成功后所有旧 token 失效，前端必须重新登录 */
    @PutMapping("/me/password")
    public Result<Void> updatePassword(@Valid @RequestBody PasswordUpdateReq req) {
        userService.updatePassword(req);
        return Result.success();
    }

    /** 注销账户：要密码二次确认；账本进回收站，15 天后物理删除 */
    @DeleteMapping("/me")
    public Result<Void> deleteMe(@Valid @RequestBody AccountDeleteReq req) {
        userService.deleteAccount(req);
        return Result.success();
    }
}