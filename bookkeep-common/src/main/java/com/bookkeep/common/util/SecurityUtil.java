package com.bookkeep.common.util;

import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 取当前登录用户 id。token 只在过滤器里解析一次，这里不重解析。
 */
public final class SecurityUtil {

    private SecurityUtil() {
    }

    /** 按 principal 类型判断：匿名请求的 principal 是字符串，isAuthenticated() 对它也是 true */
    public static Long getUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Long userId) {
            return userId;
        }
        throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或 token 失效");
    }
}