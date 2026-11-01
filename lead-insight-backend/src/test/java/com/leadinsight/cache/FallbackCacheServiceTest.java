package com.leadinsight.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 降级包装的行为单测。
 *
 * <p>三条契约：①Redis 抛数据访问异常时退到内存实现、不向上抛；
 * ②失败后进入跳过窗口，窗口内不再访问远端（2026-11-01 停机演练实测：不跳过的话
 * 每个请求都要白等 800ms 命令超时）；③序列化等编程错误必须原样抛出，不得伪装成「Redis 抖动」。</p>
 */
class FallbackCacheServiceTest {

    private CacheService primary;
    private CacheService fallback;
    private FallbackCacheService service;

    @BeforeEach
    void setUp() {
        primary = mock(CacheService.class);
        fallback = mock(CacheService.class);
        service = new FallbackCacheService(primary, fallback);
    }

    @Test
    @DisplayName("远端不可用时读操作退到内存实现")
    void getFallsBackOnConnectionFailure() {
        when(primary.get(anyString(), any())).thenThrow(new RedisConnectionFailureException("连接被拒绝"));
        when(fallback.get("k", String.class)).thenReturn("local");

        assertEquals("local", service.get("k", String.class));
    }

    @Test
    @DisplayName("首次失败后进入跳过窗口，后续调用不再访问远端")
    void skipsPrimaryAfterFailure() {
        when(primary.get(anyString(), any())).thenThrow(new RedisConnectionFailureException("连接被拒绝"));
        when(fallback.get("k", String.class)).thenReturn("local");

        service.get("k", String.class);
        service.get("k", String.class);
        service.get("k", String.class);

        verify(primary, times(1)).get(anyString(), any());
        verify(fallback, times(3)).get("k", String.class);
    }

    @Test
    @DisplayName("写操作失败也降级，且不抛异常")
    void putFallsBackAndSwallowsConnectionFailure() {
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("连接被拒绝"))
                .when(primary).put(anyString(), any(), any(Duration.class));

        assertDoesNotThrow(() -> service.put("k", "v", Duration.ofSeconds(1)));
        verify(fallback).put("k", "v", Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("自增失败时用内存计数兜底（登录失败锁定不能因 Redis 挂了就失效）")
    void incrementFallsBack() {
        when(primary.increment(anyString(), any())).thenThrow(new RedisConnectionFailureException("连接被拒绝"));
        when(fallback.increment("k", Duration.ofSeconds(60))).thenReturn(2L);

        assertEquals(2L, service.increment("k", Duration.ofSeconds(60)));
    }

    @Test
    @DisplayName("编程错误（序列化失败等）必须原样抛出，不能被当成 Redis 抖动吞掉")
    void programmingErrorPropagates() {
        when(primary.get(anyString(), any())).thenThrow(new IllegalStateException("缓存值反序列化失败"));

        assertThrows(IllegalStateException.class, () -> service.get("k", String.class));
    }

    @Test
    @DisplayName("远端正常时不触碰兜底实现")
    void happyPathNeverTouchesFallback() {
        when(primary.get("k", String.class)).thenReturn("remote");

        assertEquals("remote", service.get("k", String.class));
        verify(fallback, times(0)).get(anyString(), any());
        assertNull(service.get("missing", String.class));
    }
}
