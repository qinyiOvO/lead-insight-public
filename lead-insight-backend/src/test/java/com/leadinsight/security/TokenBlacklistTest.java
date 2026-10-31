package com.leadinsight.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登出令牌黑名单单测（DEF-07：服务端吊销，最小方案——进程内内存）。
 *
 * <p>不引 Redis、不起 Spring，直接验证四条契约：①已登出令牌在过期前按已吊销拒绝
 * ②未登出令牌不受影响（不破坏现有前端登录态）③过期条目自动淘汰
 * ④空输入安全（登出不得因空 token 变成 500）。</p>
 */
class TokenBlacklistTest {

    private final TokenBlacklist blacklist = new TokenBlacklist();

    @Test
    @DisplayName("已登出令牌在过期前按已吊销处理")
    void revokedTokenIsRejectedUntilExpiry() {
        blacklist.revoke("TST-token-logged-out", Instant.now().plusSeconds(600));
        assertTrue(blacklist.isRevoked("TST-token-logged-out"));
    }

    @Test
    @DisplayName("未登记的令牌正常放行（未登出 token 不破坏登录态）")
    void unknownTokenNotRevoked() {
        assertFalse(blacklist.isRevoked("TST-token-never-logged-out"));
    }

    @Test
    @DisplayName("已过期的吊销条目自动淘汰并返回未吊销")
    void expiredEntryIsPurgedAndReturnsFalse() {
        // 登出时 token 恰好已过期的真实场景：不得仍按已吊销处理
        blacklist.revoke("TST-token-already-expired", Instant.now().minusSeconds(60));
        assertFalse(blacklist.isRevoked("TST-token-already-expired"),
                "过期条目不得再判已吊销（否则表无限增长）");
        assertFalse(blacklist.isRevoked("TST-token-already-expired"),
                "淘汰后二次查询行为稳定");
    }

    @Test
    @DisplayName("不同令牌互不影响，null/空白令牌安全返回 false")
    void distinctTokensIsolatedAndNullSafe() {
        blacklist.revoke("TST-token-a", Instant.now().plusSeconds(600));
        assertTrue(blacklist.isRevoked("TST-token-a"));
        assertFalse(blacklist.isRevoked("TST-token-b"));
        assertFalse(blacklist.isRevoked(null));
        assertFalse(blacklist.isRevoked(""));
        assertFalse(blacklist.isRevoked("   "));
    }

    @Test
    @DisplayName("空令牌/空过期时间的登记为空操作（登出不得因此报错）")
    void revokeIgnoresInvalidInput() {
        blacklist.revoke(null, Instant.now());
        blacklist.revoke("   ", Instant.now());
        blacklist.revoke("TST-token-null-expiry", null);
        assertFalse(blacklist.isRevoked("TST-token-null-expiry"));
    }
}
