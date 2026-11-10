package com.bookkeep.service;

import com.bookkeep.model.dto.req.LoginReq;
import com.bookkeep.model.dto.req.RegisterReq;
import com.bookkeep.model.dto.resp.LoginResp;
import com.bookkeep.model.dto.resp.UserResp;

public interface AuthService {

    /** 注册：同一事务里建用户、首本账本和分类 */
    UserResp register(RegisterReq req, String clientIp);

    /** 登录；「用户不存在」和「密码错误」返回同一句提示 */
    LoginResp login(LoginReq req, String clientIp);

    /** 当前用户；userId 由 SecurityUtil 从 SecurityContext 取 */
    UserResp getCurrentUser();
}