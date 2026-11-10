package com.bookkeep.web.config;

import com.bookkeep.service.MaintenanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每天 4:40 清一次过期数据：找回密码 token、过期公示链接（见 {@code MaintenanceService}）。
 * <p>排在回收站（3:30）和票据孤儿文件（4:20）之后，三个清理任务互相不重叠。
 * <p>和回收站一样：本机不保证 7×24 开着，错过就等下一次；这些数据过期后也不会被读到，晚清不影响功能。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiredDataCleanupJob {

    private final MaintenanceService maintenanceService;

    @Scheduled(cron = "0 40 4 * * *")
    public void cleanupExpired() {
        int removed = maintenanceService.cleanupExpired();
        if (removed > 0) {
            log.info("过期数据清理完成：共 {} 行", removed);
        }
    }
}