package com.bookkeep.service;

import com.bookkeep.model.dto.req.ForgotPasswordReq;
import com.bookkeep.model.dto.req.ResetPasswordReq;

/**
 * 邮箱找回密码（PLAN §10.2）。
 * <p>两个方法都是匿名调用，所以**没有任何「账号不存在」之类的提示**：
 * 申请阶段永远静默，重置阶段只用「链接已失效」这一句。
 */
public interface PasswordResetService {

    /** 申请重置：内部判断账号是否存在、是否限流、是否已配置发信；对外一律返回成功 */
    void requestReset(ForgotPasswordReq req, String clientIp);

    /** 重置：校验一次性 token（未过期、未使用）→ 改密码 → token_version + 1 → 作废该用户其它 token */
    void resetPassword(ResetPasswordReq req);
}