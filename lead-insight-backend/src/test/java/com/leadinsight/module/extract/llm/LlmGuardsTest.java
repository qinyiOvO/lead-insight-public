package com.leadinsight.module.extract.llm;

import com.leadinsight.module.extract.config.LlmProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流（令牌桶）与熔断状态机单测（T2-04，亮点4 机制层）。
 */
class LlmGuardsTest {

    // ---------------------- 令牌桶 ----------------------

    @Test
    @DisplayName("容量耗尽后拒绝，按速率补充后恢复")
    void tokenBucketRefill() throws InterruptedException {
        LlmProperties properties = new LlmProperties();
        properties.getRateLimit().setCapacity(2);
        properties.getRateLimit().setRefillPerSecond(100.0); // 10ms 补 1 个，测试可等待
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(properties);

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire(), "容量耗尽后必须拒绝（触发指数退避）");

        Thread.sleep(50);
        assertTrue(limiter.tryAcquire(), "按速率补充后应可再次获取");
    }

    // ---------------------- 熔断 ----------------------

    @Test
    @DisplayName("连续失败达阈值 → 打开并拒绝调用；窗口过期后放行；成功即复位")
    void circuitBreakerLifecycle() throws InterruptedException {
        LlmProperties properties = new LlmProperties();
        properties.getCircuitBreaker().setFailureThreshold(3);
        properties.getCircuitBreaker().setOpenSeconds(1);
        LlmCircuitBreaker breaker = new LlmCircuitBreaker(properties);

        assertTrue(breaker.allowCall());
        breaker.recordFailure();
        breaker.recordFailure();
        assertFalse(breaker.isOpen());
        breaker.recordFailure();
        assertTrue(breaker.isOpen(), "连续失败达 3 次必须熔断");
        assertFalse(breaker.allowCall(), "熔断期内不得调用模型（直接走 DEGRADED）");

        Thread.sleep(1100);
        assertTrue(breaker.allowCall(), "熔断窗口结束后放行（半开）");

        breaker.recordSuccess();
        assertFalse(breaker.isOpen(), "成功一次即复位连续失败计数");
        assertTrue(breaker.consecutiveFailures() == 0);
    }

    @Test
    @DisplayName("失败未达阈值不熔断；成功清零计数")
    void breakerResetsOnSuccess() {
        LlmProperties properties = new LlmProperties();
        properties.getCircuitBreaker().setFailureThreshold(5);
        LlmCircuitBreaker breaker = new LlmCircuitBreaker(properties);

        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        assertTrue(breaker.consecutiveFailures() == 0);
        for (int i = 0; i < 4; i++) {
            breaker.recordFailure();
        }
        assertFalse(breaker.isOpen(), "成功复位后需重新累计，未达 5 次不熔断");
    }
}
