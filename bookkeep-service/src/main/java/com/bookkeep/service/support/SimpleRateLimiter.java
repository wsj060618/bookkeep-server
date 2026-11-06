package com.bookkeep.service.support;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内的滑动窗口限流器，只给「找回密码」用。
 * <p>为什么不用 Caffeine：全局限流是 W4 的活（PLAN §9 第 1 条），这里先给发信接口保命，
 * 不给项目多引一个依赖。W4 换 Caffeine 时把本类整块替换即可。
 * <p>单机部署够用；多实例部署时每个实例各算各的，需要改用共享存储。
 */
@Component
public class SimpleRateLimiter {

    /** 防止被随机 key 撑爆内存：超过这个数量就整体清空（宁可短暂放宽，也不 OOM） */
    private static final int MAX_KEYS = 10_000;

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /**
     * @param key    限流维度（如 {@code forgot:用户名|邮箱}、{@code forgot-ip:1.2.3.4}）
     * @param limit  窗口内允许次数
     * @param window 窗口长度
     * @return true = 放行；false = 超限
     */
    public boolean tryAcquire(String key, int limit, Duration window) {
        if (!StringUtils.hasText(key)) {
            return false;
        }
        if (hits.size() > MAX_KEYS) {
            hits.clear();
        }
        long now = System.currentTimeMillis();
        long windowStart = now - window.toMillis();

        Deque<Long> deque = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst() < windowStart) {
                deque.pollFirst();
            }
            if (deque.size() >= limit) {
                return false;
            }
            deque.addLast(now);
            return true;
        }
    }

    /** 单测用：清空所有计数（限流器是单例，测试之间会串） */
    public void clear() {
        hits.clear();
    }
}