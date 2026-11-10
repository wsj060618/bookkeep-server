package com.bookkeep.web.config;

import com.bookkeep.service.ReceiptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 票据孤儿文件清理：每天 4:20 扫一遍 `data/receipts`，删掉库里没有记录的图片。
 * <p>为什么会有孤儿：上传是「先落盘、后插库」，插库失败/事务回滚会留下文件；
 * 手工改库、迁移只拷了一半也一样。
 * <p>{@code minAge = 1 小时}：只清理「早就写好」的文件，避免删掉正在上传、还没来得及入库的那张。
 * 删不掉只记日志，不能让定时任务挂掉。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReceiptOrphanJob {

    private static final Duration MIN_AGE = Duration.ofHours(1);

    private final ReceiptService receiptService;

    @Scheduled(cron = "0 20 4 * * *")
    public void purgeOrphans() {
        int removed = receiptService.purgeOrphanFiles(MIN_AGE);
        if (removed > 0) {
            log.info("票据孤儿文件定时清理完成：{} 个", removed);
        }
    }
}