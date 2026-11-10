package com.bookkeep.service;

/**
 * 定时清理过期数据：找回密码 token、过期公示链接（含成员行）。
 * <p>只删已经过期、不可能再被读到的行。
 */
public interface MaintenanceService {

    /** @return 删掉的总行数 */
    int cleanupExpired();
}