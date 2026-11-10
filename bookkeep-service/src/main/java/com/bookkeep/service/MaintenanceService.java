package com.bookkeep.service;

/**
 * 定时清理过期数据。
 * <p>只删「过了有效期就再也不可能被使用」的行，不碰任何用户可见数据：
 * <ol>
 *   <li>`password_reset_token`：`expires_at < now()`（兑换时本来就要求未过期）</li>
 *   <li>`share_link` + `share_link_book`：`expires_at < now()`（公示页过期即 404）</li>
 * </ol>
 * <p>为什么要有：这两张表只在「找回密码 / 点分享」时写一行，平时没人删，长期跑会一直涨。
 * 量级很小，但既然是自动生成的数据，清理掉比留着更省心。
 */
public interface MaintenanceService {

    /**
     * 清一次过期数据。
     *
     * @return 删掉的总行数（token + 链接 + 成员行），0 表示没有可清理的
     */
    int cleanupExpired();
}