package com.leadinsight.cache;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存缓存实现（M0/M1 默认）。基于 ConcurrentHashMap + 惰性过期，
 * 不引入 Caffeine 等外部依赖；R1 不依赖 Redis。
 *
 * <p>注意：单进程内存缓存，多实例部署时不共享；最终一致性真源仍是数据库
 * （如线索指纹以 MySQL 唯一索引 uk_lead_fingerprint 兜底）。</p>
 */
@Service
public class InMemoryCacheService implements CacheService {

    /** 每 N 次写入触发一次过期键清理，避免无界增长 */
    private static final int SWEEP_INTERVAL = 1024;

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final AtomicLong writeCounter = new AtomicLong();

    private record Entry(Object value, long expireAtMillis) {
        boolean expired(long now) {
            return now >= expireAtMillis;
        }
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        store.put(key, newEntry(value, ttl));
        maybeSweep();
    }

    @Override
    public Object get(String key) {
        Entry entry = store.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expired(System.currentTimeMillis())) {
            store.remove(key, entry);
            return null;
        }
        return entry.value();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object value = get(key);
        if (value == null || !type.isInstance(value)) {
            return null;
        }
        return (T) value;
    }

    @Override
    public boolean putIfAbsent(String key, Object value, Duration ttl) {
        long now = System.currentTimeMillis();
        Entry candidate = newEntry(value, ttl);
        Entry result = store.compute(key, (k, existing) ->
                (existing == null || existing.expired(now)) ? candidate : existing);
        maybeSweep();
        // 引用比较：只有本次真正写入才会返回 candidate 本身
        return result == candidate;
    }

    @Override
    public void delete(String key) {
        store.remove(key);
    }

    @Override
    public long increment(String key, Duration ttl) {
        long now = System.currentTimeMillis();
        Entry updated = store.compute(key, (k, existing) -> {
            if (existing == null || existing.expired(now)) {
                return new Entry(1L, expireAt(ttl));
            }
            long current = existing.value() instanceof Number n ? n.longValue() : 0L;
            return new Entry(current + 1, existing.expireAtMillis());
        });
        maybeSweep();
        return updated.value() instanceof Number n ? n.longValue() : 0L;
    }

    private Entry newEntry(Object value, Duration ttl) {
        return new Entry(value, expireAt(ttl));
    }

    private long expireAt(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return Long.MAX_VALUE;
        }
        long millis = ttl.toMillis();
        long now = System.currentTimeMillis();
        return millis >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + millis;
    }

    private void maybeSweep() {
        if (writeCounter.incrementAndGet() % SWEEP_INTERVAL != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> e.getValue().expired(now));
    }
}
