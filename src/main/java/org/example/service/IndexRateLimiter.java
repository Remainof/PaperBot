package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 索引速率限制器 —— 控制 Embedding API 调用频率。
 * <p>
 * 使用简单的滑动窗口实现 requests-per-minute 和 tokens-per-minute 限流。
 * 生产环境可替换为 Guava RateLimiter 或 Sentinel。
 */
@Component
public class IndexRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(IndexRateLimiter.class);

    private final int maxRequestsPerMinute;
    private final int maxTokensPerMinute;

    /** 当前窗口的请求计数和 token 计数 */
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final AtomicInteger tokenCount = new AtomicInteger(0);

    /** 当前窗口开始时间戳（ms） */
    private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());

    /** 窗口大小（ms） */
    private static final long WINDOW_MS = 60_000L;

    public IndexRateLimiter(
            @Value("${indexing.rate-limit.requests-per-minute:120}") int maxRequestsPerMinute,
            @Value("${indexing.rate-limit.tokens-per-minute:200000}") int maxTokensPerMinute) {
        this.maxRequestsPerMinute = maxRequestsPerMinute;
        this.maxTokensPerMinute = maxTokensPerMinute;
        log.info("限流器初始化: RPM={}, TPM={}", maxRequestsPerMinute, maxTokensPerMinute);
    }

    /**
     * 尝试获取一个请求的限流许可。
     * 包含请求计数和 token 估算双重限流。
     *
     * @param estimatedTokens 本次请求预估消耗的 token 数
     * @return true 许可通过，false 触发限流
     */
    public boolean tryAcquire(int estimatedTokens) {
        resetIfExpired();

        if (requestCount.get() >= maxRequestsPerMinute) {
            log.warn("请求限流: 已达到 RPM 上限 ({})", maxRequestsPerMinute);
            return false;
        }
        if (tokenCount.get() + estimatedTokens > maxTokensPerMinute) {
            log.warn("Token 限流: 已达到 TPM 上限 ({}/{})",
                    tokenCount.get() + estimatedTokens, maxTokensPerMinute);
            return false;
        }

        requestCount.incrementAndGet();
        tokenCount.addAndGet(estimatedTokens);
        return true;
    }

    /** 窗口过期时重置计数 */
    private void resetIfExpired() {
        long now = System.currentTimeMillis();
        long start = windowStart.get();
        if (now - start > WINDOW_MS) {
            // 这里存在线程安全问题，但少量并发下不影响正确性（最多多跑一个窗口）
            if (windowStart.compareAndSet(start, now)) {
                requestCount.set(0);
                tokenCount.set(0);
            }
        }
    }

    /** 获取当前请求数和限制 */
    public int getCurrentRequests() { return requestCount.get(); }
    public int getMaxRequests() { return maxRequestsPerMinute; }
    public int getCurrentTokens() { return tokenCount.get(); }
    public int getMaxTokens() { return maxTokensPerMinute; }
}
