package com.leadinsight.security;

import com.leadinsight.cache.CacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登出黑名单的远端二级（L2）行为单测。
 *
 * <p>与 {@link TokenBlacklistTest}（只测 L1 契约）互补：这里验证「跨实例吊销」与
 * 「L2 故障时 fail-open 到 L1」两条 M2 新增语义。用 Mockito 模拟 {@link CacheService}，
 * 不起 Spring、不连 Redis。</p>
 */
class TokenBlacklistRemoteTest {

    private CacheService cacheService;
    private TokenBlacklist blacklist;

    @BeforeEach
    void setUp() {
        cacheService = mock(CacheService.class);
        blacklist = new TokenBlacklist(cacheService);
    }

    @Test
    @DisplayName("登出时双写 L2，TTL 取令牌剩余有效期而非写死")
    void revokeWritesThroughWithRemainingTtl() {
        blacklist.revoke("TST-token-double-write", Instant.now().plusSeconds(600));

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(cacheService).put(org.mockito.ArgumentMatchers.startsWith("auth:revoked:"),
                eq("1"), ttl.capture());

        long seconds = ttl.getValue().toSeconds();
        assertTrue(seconds > 590 && seconds <= 600,
                "TTL 应约等于 600 秒剩余有效期，实际=" + seconds);
        assertTrue(blacklist.isRevoked("TST-token-double-write"), "L1 必须同时生效");
    }

    @Test
    @DisplayName("本实例 L1 未命中但 L2 命中时仍判已吊销（等价于另一实例登出过）")
    void revokedByAnotherInstanceIsRejected() {
        when(cacheService.get(org.mockito.ArgumentMatchers.startsWith("auth:revoked:"), eq(String.class)))
                .thenReturn("1");

        assertTrue(blacklist.isRevoked("TST-token-revoked-elsewhere"),
                "跨实例吊销失效就意味着 Redis 白接了");
    }

    @Test
    @DisplayName("L2 查询抛异常时按未吊销处理，不把请求打成 500")
    void remoteFailureFailsOpen() {
        when(cacheService.get(anyString(), eq(String.class)))
                .thenThrow(new IllegalStateException("Redis 挂了"));

        assertDoesNotThrow(() -> blacklist.isRevoked("TST-token-l2-down"));
        assertFalse(blacklist.isRevoked("TST-token-l2-down"));
    }

    @Test
    @DisplayName("L2 写入抛异常时不阻断登出，且 L1 仍然生效")
    void remoteWriteFailureDoesNotBreakLogout() {
        doThrow(new IllegalStateException("Redis 挂了")).when(cacheService)
                .put(anyString(), any(), any(Duration.class));

        assertDoesNotThrow(() -> blacklist.revoke("TST-token-write-fail", Instant.now().plusSeconds(600)));
        assertTrue(blacklist.isRevoked("TST-token-write-fail"),
                "远端写失败不得影响本实例的吊销效果");
    }

    @Test
    @DisplayName("登出时令牌已过期则不写 L2（黑名单不得比令牌活得更久）")
    void alreadyExpiredTokenIsNotWritten() {
        blacklist.revoke("TST-token-expired", Instant.now().minusSeconds(30));

        verify(cacheService, never()).put(anyString(), any(), any(Duration.class));
        assertFalse(blacklist.isRevoked("TST-token-expired"));
    }

    @Test
    @DisplayName("未装配 L2 时行为与改造前一致（单参构造，只用 L1）")
    void localOnlyModeStillWorks() {
        TokenBlacklist localOnly = new TokenBlacklist();
        localOnly.revoke("TST-token-local-only", Instant.now().plusSeconds(600));

        assertTrue(localOnly.isRevoked("TST-token-local-only"));
        assertFalse(localOnly.isRevoked("TST-token-other"));
    }
}
