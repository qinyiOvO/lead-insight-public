package com.leadinsight.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CacheService} 的<b>接口契约</b>：同一套断言必须同时约束内存实现与 Redis 实现。
 *
 * <p>为什么需要它：M2 的价值前提是「换实现不改业务」，而两个实现的差异往往藏在边界里——
 * 原值类型、过期残留、自增是否续期。这些差异一旦漏掉，表现是「功能还在但防不住了」，
 * 比直接报错危险得多。10 条断言逐条对应一个已知坑，注释里写了各自防的是什么。</p>
 *
 * <p>键名统一加随机后缀，避免两个实现共用同一 Redis 库时互相污染。</p>
 */
abstract class AbstractCacheServiceContractTest {

    /** 由子类提供具体实现 */
    protected abstract CacheService createCache();

    protected CacheService cache;

    @BeforeEach
    void setUp() {
        cache = createCache();
    }

    private String key(String name) {
        return "contract:" + name + ":" + UUID.randomUUID();
    }

    @Test
    @DisplayName("1. put 后能按类型读回同值")
    void putThenTypedGet() {
        String k = key("put");
        cache.put(k, "hello", Duration.ofMinutes(1));
        assertEquals("hello", cache.get(k, String.class));
    }

    @Test
    @DisplayName("2. 读不存在的键返回 null，且不抛异常")
    void missReturnsNull() {
        String k = key("miss");
        assertNull(cache.get(k));
        assertNull(cache.get(k, Long.class));
        assertNull(cache.get(k, String.class));
    }

    @Test
    @DisplayName("3. 小整数必须读回 Long，不能变成 Integer（否则调用方静默失效）")
    void smallIntegerStaysLong() {
        String k = key("long");
        cache.put(k, 42L, Duration.ofMinutes(1));
        Object typed = cache.get(k, Long.class);
        assertInstanceOf(Long.class, typed, "Typed get 必须返回 Long，否则 (Long) 强转会失败、isInstance 判断会返回 null");
        assertEquals(42L, typed);
    }

    @Test
    @DisplayName("4. TTL 到点后读不到")
    void expiredReturnsNull() throws InterruptedException {
        String k = key("ttl");
        cache.put(k, "v", Duration.ofMillis(200));
        assertEquals("v", cache.get(k, String.class));
        Thread.sleep(350);
        assertNull(cache.get(k, String.class));
    }

    @Test
    @DisplayName("5. putIfAbsent 首次成功、二次失败且不覆盖旧值（原子性）")
    void putIfAbsentIsAtomicAndKeepsFirst() {
        String k = key("nx");
        assertTrue(cache.putIfAbsent(k, "first", Duration.ofMinutes(1)));
        assertFalse(cache.putIfAbsent(k, "second", Duration.ofMinutes(1)));
        assertEquals("first", cache.get(k, String.class));
    }

    @Test
    @DisplayName("6. 键已过期时 putIfAbsent 视为不存在，允许写入")
    void putIfAbsentAfterExpirySucceeds() throws InterruptedException {
        String k = key("nx-expired");
        assertTrue(cache.putIfAbsent(k, "first", Duration.ofMillis(150)));
        Thread.sleep(300);
        assertTrue(cache.putIfAbsent(k, "second", Duration.ofMillis(150)),
                "过期残留键必须被当作不存在，否则去重会永久卡死");
        assertEquals("second", cache.get(k, String.class));
    }

    @Test
    @DisplayName("7. increment 首次返回 1")
    void incrementStartsAtOne() {
        assertEquals(1L, cache.increment(key("inc"), Duration.ofSeconds(10)));
    }

    @Test
    @DisplayName("8. increment 第二次返回 2，且不续期 TTL（固定窗口，不是滚动窗口）")
    void incrementDoesNotExtendTtl() throws InterruptedException {
        String k = key("inc-ttl");
        long ttlMillis = 1200;
        assertEquals(1L, cache.increment(k, Duration.ofMillis(ttlMillis)));
        Thread.sleep(700);
        assertEquals(2L, cache.increment(k, Duration.ofMillis(ttlMillis)));
        // 再等到「从首次自增起」已超过 ttl：若第二次自增续期了，此处仍是 3
        Thread.sleep(700);
        assertEquals(1L, cache.increment(k, Duration.ofMillis(ttlMillis)),
                "自增不得续期 TTL；续期会让登录失败锁定变成『最后一次失败后 N 秒』的无限期拒绝");
    }

    @Test
    @DisplayName("9. 过期后 increment 从 1 重新开始")
    void incrementRestartsAfterExpiry() throws InterruptedException {
        String k = key("inc-expire");
        assertEquals(1L, cache.increment(k, Duration.ofMillis(150)));
        Thread.sleep(300);
        assertEquals(1L, cache.increment(k, Duration.ofMillis(150)));
    }

    @Test
    @DisplayName("10. delete 后读不到；删不存在的键不抛异常（幂等）")
    void deleteIsIdempotent() {
        String k = key("del");
        cache.put(k, "v", Duration.ofMinutes(1));
        cache.delete(k);
        assertNull(cache.get(k, String.class));
        cache.delete(k);
    }
}
