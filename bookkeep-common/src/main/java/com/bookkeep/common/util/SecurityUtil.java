package com.bookkeep.common.util;

import com.bookkeep.common.exception.BusinessException;
import com.bookkeep.common.result.ResultCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户工具：从 SecurityContext 取 userId。
 * <p>放 common 模块：只依赖 {@code spring-security-core}（本模块已有），service 层每个业务都能直接用，
 * 不必经过 web 层，也不会反向依赖 controller。
 * <p><strong>不要在这里重新解析 token</strong>：token 只在 {@code JwtAuthenticationFilter} 里解析一次，
 * 之后全链路都用这里取到的 userId，否则「过滤器认了谁」和「业务以为是谁」可能不一致。
 */
public final class SecurityUtil {

    private SecurityUtil() {
    }

    /**
     * 当前登录用户 id。
     * <p>principal 由过滤器放入，固定是 {@code Long}（token 的 subject）。
     * 匿名请求（未带 token / token 无效）的 principal 是字符串 {@code "anonymousUser"}，
     * 这里统一按类型判断，不信任 {@code isAuthenticated()}：
     * 后者对匿名 token 也返回 true，用它判断会把匿名当登录。
     *
     * @throws BusinessException code=401，未登录 / token 无效 / 用户已注销
     */
    public static Long getUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Long userId) {
            return userId;
        }
        throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或 token 失效");
    }
}
