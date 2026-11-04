package com.bookkeep.web.config;

import com.bookkeep.service.BookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 回收站清理任务：每天 3:30 彻底删除满 15 天的账本（连同分类、流水）。
 * <p>为什么要定时 + 顺手清理两条路：本机不保证 7×24 开机，停机期间错过的清理
 * 由 {@code BookService#list} 在用户下次打开时补做（见 BookServiceImpl#list）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookPurgeJob {

    private final BookService bookService;

    @Scheduled(cron = "0 30 3 * * *")
    public void purgeExpiredBooks() {
        int purged = bookService.purgeExpired();
        if (purged > 0) {
            log.info("回收站定时清理完成：{} 本", purged);
        }
    }
}