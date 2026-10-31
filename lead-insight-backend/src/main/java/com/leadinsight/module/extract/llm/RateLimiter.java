package com.leadinsight.module.extract.llm;

/**
 * 模型调用限流抽象（T2-04，ADR-005 方案 A）。
 * M0/M1 内存令牌桶实现；M3 切 Redis+Lua 滑动窗口，业务零改动。
 */
public interface RateLimiter {

    /**
     * 尝试获取一个令牌（不阻塞）。
     *
     * @return true=获取成功可调用模型；false=令牌不足（触发指数退避重试）
     */
    boolean tryAcquire();
}
