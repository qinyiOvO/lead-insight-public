package com.leadinsight.module.extract.llm;

import com.leadinsight.module.extract.config.LlmProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模型调用熔断器（T2-04，ADR-005 降级第二档）。
 *
 * <p>连续失败达阈值（默认 5 次）→ 熔断 N 分钟（默认 300s），期内 {@link #allowCall()}
 * 返回 false，调用方直接走 DEGRADED（线索照常落库，主流程不受影响）。
 * 成功一次即清零连续失败计数。进程内状态机（单机部署可接受，ADR-005 已登记）。</p>
 */
@Component
public class LlmCircuitBreaker {

    private final int failureThreshold;
    private final long openMillis;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong openUntilNanos = new AtomicLong(0);

    public LlmCircuitBreaker(LlmProperties properties) {
        this.failureThreshold = Math.max(1, properties.getCircuitBreaker().getFailureThreshold());
        this.openMillis = Math.max(1, properties.getCircuitBreaker().getOpenSeconds()) * 1000L;
    }

    /** 熔断打开期内返回 false（不调用模型，直接降级） */
    public boolean allowCall() {
        long until = openUntilNanos.get();
        if (until == 0) {
            return true;
        }
        if (System.nanoTime() < until) {
            return false;
        }
        // 熔断窗口结束，回到半开：放行一次尝试，连续失败计数从当前值继续累计
        openUntilNanos.set(0);
        return true;
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        openUntilNanos.set(0);
    }

    public void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openUntilNanos.set(System.nanoTime() + openMillis * 1_000_000L);
        }
    }

    public boolean isOpen() {
        return !allowCall();
    }

    /** 当前连续失败次数（观测/测试用） */
    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }
}
