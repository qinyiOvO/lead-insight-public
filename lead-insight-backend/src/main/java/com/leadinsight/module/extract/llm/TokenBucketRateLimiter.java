package com.leadinsight.module.extract.llm;

import com.leadinsight.module.extract.config.LlmProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存令牌桶（ADR-005：并发正确性用单锁保证，桶状态一个监视器对象内完成 refill+take，
 * 避免「检查-扣减」竞态）。
 *
 * <p>容量 = 突发上限；按 {@code refillPerSecond} 匀速补充。M3 换 Redis+Lua 实现同接口。</p>
 */
@Component
public class TokenBucketRateLimiter implements RateLimiter {

    private final long capacity;
    private final double refillPerSecond;

    private final Object lock = new Object();
    private double tokens;
    private long lastRefillNanos;

    public TokenBucketRateLimiter(LlmProperties properties) {
        this.capacity = Math.max(1, properties.getRateLimit().getCapacity());
        this.refillPerSecond = Math.max(0.001, properties.getRateLimit().getRefillPerSecond());
        this.tokens = this.capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    @Override
    public boolean tryAcquire() {
        synchronized (lock) {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    /** 仅供测试观察当前余量 */
    double availableTokens() {
        synchronized (lock) {
            refill();
            return tokens;
        }
    }

    private void refill() {
        long now = System.nanoTime();
        double added = (now - lastRefillNanos) / 1_000_000_000.0 * refillPerSecond;
        if (added > 0) {
            tokens = Math.min(capacity, tokens + added);
            lastRefillNanos = now;
        }
    }
}
