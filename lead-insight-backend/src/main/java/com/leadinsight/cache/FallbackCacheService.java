package com.leadinsight.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 降级包装：Redis 可用时走 {@code primary}，发生<b>数据访问类</b>异常时退到 {@code fallback}（内存实现）。
 *
 * <p><b>为什么必须包一层：</b>Lettuce 是懒连接——应用启动时不会连 Redis，第一次读写才建连。
 * 若不包装，Redis 不可用时 {@code AuthServiceImpl} 的登录失败计数与
 * {@code LeadDedupServiceImpl} 的指纹快路径会直接抛连接异常，接口变成 500；
 * 而这两处业务上都不该因为「缓存没了」而失败（真源分别是数据库与 MySQL 唯一索引）。</p>
 *
 * <p><b>只捕获 {@link DataAccessException}：</b>连接断开、超时都在这一类；
 * 序列化/反序列化异常属于代码缺陷，必须原样抛出——否则会把 bug 伪装成「Redis 抖动」，
 * 这是最容易把自己骗过去的地方。</p>
 *
 * <p><b>降级后会「降级到什么」：</b>内存实现，即本进程内的旧行为。这意味着多实例共享能力暂时消失，
 * 但功能不退化为报错。WARN 日志按 60 秒节流，避免 Redis 长时间不可用时刷满磁盘。</p>
 *
 * <p><b>为什么还要「跳过窗口」（2026-11-01 停机演练实测补的）：</b>Redis 进程被杀后，
 * Lettuce 不会立刻抛错，而是把命令排进重连队列并等满命令超时（本项目 800ms）。
 * 由于认证过滤器对<b>每个请求</b>都要查一次登出黑名单，实测单请求从 ~10ms 涨到 <b>840ms</b>——
 * 降级本身没问题，但「每次都先试一次再失败」的代价不可接受。
 * 故失败后置 5 秒跳过窗口，窗口内直接走兜底实现，窗口结束再试一次（半开）。
 * 这等价于给缓存层加了个极简熔断：不引入额外组件，却把故障期的尾延迟压回正常量级。</p>
 */
@Slf4j
public class FallbackCacheService implements CacheService {

    /** 告警节流：同一故障 60 秒内只打一条 WARN */
    private static final long WARN_INTERVAL_MILLIS = 60_000L;
    /** 失败后跳过 Redis 的窗口：窗口内直接走兜底，避免每个请求都白等一次超时 */
    private static final long SKIP_PRIMARY_MILLIS = 5_000L;

    private final CacheService primary;
    private final CacheService fallback;
    private final AtomicLong lastWarnAt = new AtomicLong(0L);
    private final AtomicLong skipPrimaryUntil = new AtomicLong(0L);

    public FallbackCacheService(CacheService primary, CacheService fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        if (skipPrimary()) {
            fallback.put(key, value, ttl);
            return;
        }
        try {
            primary.put(key, value, ttl);
        } catch (DataAccessException e) {
            onFailure("put", e);
            fallback.put(key, value, ttl);
        }
    }

    @Override
    public Object get(String key) {
        if (skipPrimary()) {
            return fallback.get(key);
        }
        try {
            return primary.get(key);
        } catch (DataAccessException e) {
            onFailure("get", e);
            return fallback.get(key);
        }
    }

    @Override
    public <T> T get(String key, Class<T> type) {
        if (skipPrimary()) {
            return fallback.get(key, type);
        }
        try {
            return primary.get(key, type);
        } catch (DataAccessException e) {
            onFailure("get(typed)", e);
            return fallback.get(key, type);
        }
    }

    @Override
    public boolean putIfAbsent(String key, Object value, Duration ttl) {
        if (skipPrimary()) {
            return fallback.putIfAbsent(key, value, ttl);
        }
        try {
            return primary.putIfAbsent(key, value, ttl);
        } catch (DataAccessException e) {
            onFailure("putIfAbsent", e);
            return fallback.putIfAbsent(key, value, ttl);
        }
    }

    @Override
    public void delete(String key) {
        if (skipPrimary()) {
            fallback.delete(key);
            return;
        }
        try {
            primary.delete(key);
        } catch (DataAccessException e) {
            onFailure("delete", e);
            fallback.delete(key);
        }
    }

    @Override
    public long increment(String key, Duration ttl) {
        if (skipPrimary()) {
            return fallback.increment(key, ttl);
        }
        try {
            return primary.increment(key, ttl);
        } catch (DataAccessException e) {
            onFailure("increment", e);
            return fallback.increment(key, ttl);
        }
    }

    /** 窗口内直接走兜底；窗口结束后会再试一次（半开），成功即恢复全速 */
    private boolean skipPrimary() {
        return System.currentTimeMillis() < skipPrimaryUntil.get();
    }

    private void onFailure(String operation, DataAccessException e) {
        skipPrimaryUntil.set(System.currentTimeMillis() + SKIP_PRIMARY_MILLIS);
        long now = System.currentTimeMillis();
        long last = lastWarnAt.get();
        if (now - last >= WARN_INTERVAL_MILLIS && lastWarnAt.compareAndSet(last, now)) {
            log.warn("Redis 不可用，缓存已降级为进程内实现并跳过远端 {} 毫秒（本次操作={}，60 秒内不再重复告警）: {}",
                    SKIP_PRIMARY_MILLIS, operation, e.getMessage());
        } else {
            log.debug("Redis 不可用，本次操作已降级: operation={}", operation);
        }
    }
}
