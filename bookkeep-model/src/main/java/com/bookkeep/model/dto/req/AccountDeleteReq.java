package com.bookkeep.model.dto.req;

import jakarta.validation.constraints.NotBlank;

/**
 * 注销账户（DELETE /api/users/me）。
 * <p>要密码是防「手机没锁屏时被别人一键注销」；注销不可自助恢复（见 PLAN §5.3）。
 */
public record AccountDeleteReq(

        @NotBlank(message = "密码不能为空")
        String password
) {
}