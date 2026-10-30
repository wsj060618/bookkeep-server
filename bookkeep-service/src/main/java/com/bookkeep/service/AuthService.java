package com.bookkeep.service;

import com.bookkeep.model.dto.req.LoginReq;
import com.bookkeep.model.dto.req.RegisterReq;
import com.bookkeep.model.dto.resp.LoginResp;
import com.bookkeep.model.dto.resp.UserResp;

/**
 * 认证业务：注册建账本、登录发 token（第 5 天）
 * <p>注册和登录都涉及多表写入 / 校验，逻辑放 service 层，controller 只做参数接收与包装。
 */
public interface AuthService {

    /**
     * 注册用户，并在同一事务里创建第一本账本 + 预置分类模板。
     *
     * @param req 注册请求（用户名、密码、账本名、账本类型…）
     * @return 新用户信息（含 currentBookId，不含密码）
     */
    UserResp register(RegisterReq req);

    /**
     * 登录：校验密码 → 签发 JWT → 连带返回用户信息和全部账本。
     * <p>「用户不存在」和「密码错误」对外返回同一句提示，避免接口被拿来枚举用户名。
     *
     * @param req 登录请求（用户名、明文密码）
     * @return token + 用户信息 + 账本列表（{@code current} 标出当前账本）
     * @throws com.bookkeep.common.exception.BusinessException 用户名或密码错误（code=400）
     */
    LoginResp login(LoginReq req);
}
