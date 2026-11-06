package com.bookkeep.service;

import com.bookkeep.model.dto.req.AccountDeleteReq;
import com.bookkeep.model.dto.req.PasswordUpdateReq;
import com.bookkeep.model.dto.req.UserUpdateReq;
import com.bookkeep.model.dto.resp.UserResp;

/**
 * 用户自助管理（PLAN §5.3）：改资料 / 改密码 / 注销账户。
 * <p>三个方法都只作用于「当前登录用户」（{@code SecurityUtil.getUserId()}），不接受客户端传 userId。
 */
public interface UserService {

    /** 改昵称 / 邮箱（空串会被当成 null，即清空） */
    UserResp updateProfile(UserUpdateReq req);

    /** 改密码：校验旧密码，成功后 token_version + 1（所有旧 token 立即失效） */
    void updatePassword(PasswordUpdateReq req);

    /** 注销：校验密码，软删本人 + 名下账本（账本进 15 天回收站） */
    void deleteAccount(AccountDeleteReq req);
}