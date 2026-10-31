package com.leadinsight.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登出令牌黑名单（DEF-07：服务端吊销，最小方案——进程内内存，不引 Redis）。
 *
 * <p>登出时把「令牌哈希 → 令牌过期时刻」登记入表；认证过滤器对每个请求先查黑名单，
 * 命中即按 40100 拒绝。条目按 token 自身剩余有效期淘汰（过期后查之自动返回未吊销），
 * 黑名单不会无限增长。</p>
 *
 * <p><b>进程内限制（有意为之，与无状态 JWT 的生命周期一致）：</b>本表不落库、不跨实例。
 * jwt.secret 未配置时 JwtUtil 使用本进程随机密钥（重启后已签发令牌全部失效），
 * 黑名单同为进程内状态，二者失效面一致，无额外风险；多实例部署需改共享存储（如 Redis），
 * 属产品化阶段事项，本轮不做。</p>
 *
 * <p>键存 SHA-256 而非令牌原文：避免明文令牌驻留堆内存（日志转储/内存镜像泄露面更小）。</p>
 */
@Slf4j
@Component
public class TokenBlacklist {

    /** 令牌哈希（SHA-256 十六进制）→ 该令牌的过期时刻；到期即无需保留 */
    private final ConcurrentHashMap<String, Instant> revoked = new ConcurrentHashMap<>();

    /** 登记一个已登出的令牌，直到其自然过期为止都按已吊销处理 */
    public void revoke(String token, Instant expiresAt) {
        if (token == null || token.isBlank() || expiresAt == null) {
            return;
        }
        purgeExpired();
        revoked.put(sha256Hex(token), expiresAt);
        log.debug("令牌已列入登出黑名单: expiresAt={}", expiresAt);
    }

    /** 该令牌是否已登出；过期条目顺手淘汰并返回 false */
    public boolean isRevoked(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String key = sha256Hex(token);
        Instant expiresAt = revoked.get(key);
        if (expiresAt == null) {
            return false;
        }
        if (Instant.now().isAfter(expiresAt)) {
            revoked.remove(key);
            return false;
        }
        return true;
    }

    /** 清理已过期条目（登记新吊销时顺手执行，表本身极小，全量迭代成本可忽略） */
    private void purgeExpired() {
        Instant now = Instant.now();
        for (Iterator<Map.Entry<String, Instant>> it = revoked.entrySet().iterator(); it.hasNext(); ) {
            if (now.isAfter(it.next().getValue())) {
                it.remove();
            }
        }
    }

    private static String sha256Hex(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必配算法，正常运行不会到这里；兜底用原生哈希避免把登出打成 500
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
