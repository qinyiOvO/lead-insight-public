package com.leadinsight.cache;

import java.time.Duration;

/**
 * 缓存抽象层。M0/M1 使用内存实现（无 Redis 依赖），M2 可切换 Redis 实现，
 * 业务代码零改动（ARCHITECTURE-v1.1 §2.3 两阶段接入策略）。
 */
public interface CacheService {

    /** 写入并设置过期时间 */
    void put(String key, Object value, Duration ttl);

    /** 读取，不存在或已过期返回 null */
    Object get(String key);

    /** 读取并做类型转换 */
    <T> T get(String key, Class<T> type);

    /**
     * 仅当键不存在（或已过期）时写入。
     *
     * @return true=本次写入成功（此前不存在）；false=键已存在，未覆盖
     */
    boolean putIfAbsent(String key, Object value, Duration ttl);

    /** 删除键 */
    void delete(String key);

    /**
     * 计数自增（键不存在则从 0 开始），并保证键具有过期时间。
     * 用于登录失败次数等滑动窗口计数。
     *
     * @return 自增后的当前值
     */
    long increment(String key, Duration ttl);
}
