package com.bookkeep.service.impl;

import com.bookkeep.service.MaintenanceService;
import com.bookkeep.service.mapper.PasswordResetTokenMapper;
import com.bookkeep.service.mapper.ShareLinkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 过期数据清理（口径见 {@link MaintenanceService}）。
 * <p>三条 DELETE 放在一个事务里：先成员行再链接，避免中途失败留下孤儿成员行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaintenanceServiceImpl implements MaintenanceService {

    private final PasswordResetTokenMapper passwordResetTokenMapper;
    private final ShareLinkMapper shareLinkMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cleanupExpired() {
        int tokens = passwordResetTokenMapper.deleteExpired();
        int bookships = shareLinkMapper.deleteExpiredBookships();
        int links = shareLinkMapper.deleteExpired();
        int total = tokens + bookships + links;
        if (total > 0) {
            log.info("清理过期数据：找回密码 token {} 条，过期公示链接 {} 条（成员行 {} 条）", tokens, links, bookships);
        }
        return total;
    }
}