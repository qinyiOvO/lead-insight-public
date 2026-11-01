package com.leadinsight.security;

import com.leadinsight.cache.CacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登出令牌黑名单（DEF-07：服务端吊销）。
 *
 * <p>登出时把「令牌哈希 → 令牌过期时刻」登记入表；认证过滤器对每个请求先查黑名单，
 * 命中即按 40100 拒绝。条目按 token 自身剩余有效期淘汰（过期后查之自动返回未吊销），
 * 黑名单不会无限增长。</p>
 *
 * <p><b>两级结构（M2）：</b>本地 L1（本进程 {@link ConcurrentHashMap}）+ 远端 L2（经
 * {@link CacheService}，memory 模式下退化为同进程、redis 模式下即 Redis）。
 * 登出时两边都写；校验时 L1 命中即返回（零网络开销，本进程登出的令牌必然命中），
 * L1 未命中再查 L2——这样 A 实例登出的令牌在 B 实例同样失效，是纯单机方案给不了的能力。</p>
 *
 * <p><b>失效姿态：</b>L2 不可用时只降级为「本实例生效」（fail-open 到 L1），
 * 不抛异常、不把全站打成 401。理由：L1 已覆盖本实例登出的令牌，
 * 丢失的只是跨实例吊销这一增强项，而抛 500 会放大成全局故障。</p>
 *
 * <p><b>与 jwt.secret 的关系：</b>未配置 secret 时 {@link JwtUtil} 用本进程随机密钥，
 * 重启后旧令牌本就签名失败，L1 清空无风险；配了外部 secret 时重启后旧令牌仍有效，
 * 此时 L2 恰好补上「重启后已登出令牌复活」这个安全缺口。</p>
 *
 * <p>键存 SHA-256 而非令牌原文：避免明文令牌驻留堆内存/ Redis，日志转储与内存镜像的泄露面更小。</p>
 */
@Slf4j
@Component
public class TokenBlacklist {

    /** L2 键前缀（{@link CacheService} 实现会再拼全局命名空间前缀） */
    private static final String REMOTE_KEY_PREFIX = "auth:revoked:";
    /** L2 值只需表达「存在即已吊销」，用定长常量避免存入任何令牌派生信息 */
    private static final String REMOTE_VALUE = "1";

    /** 令牌哈希（SHA-256 十六进制）→ 该令牌的过期时刻；到期即无需保留 */
    private final ConcurrentHashMap<String, Instant> revoked = new ConcurrentHashMap<>();

    /** 远端二级存储；为 null 表示仅用 L1（单测与最小装配） */
    private final CacheService cacheService;

    /** 仅 L1（保持既有单测 {@code new TokenBlacklist()} 可用） */
    public TokenBlacklist() {
        this(null);
    }

    @Autowired
    public TokenBlacklist(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    /** 登记一个已登出的令牌，直到其自然过期为止都按已吊销处理 */
    public void revoke(String token, Instant expiresAt) {
        if (token == null || token.isBlank() || expiresAt == null) {
            return;
        }
        purgeExpired();
        String hash = sha256Hex(token);
        revoked.put(hash, expiresAt);
        writeThrough(hash, expiresAt);
        log.debug("令牌已列入登出黑名单: expiresAt={}", expiresAt);
    }

    /** 该令牌是否已登出；过期条目顺手淘汰并返回 false */
    public boolean isRevoked(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String hash = sha256Hex(token);
        Instant expiresAt = revoked.get(hash);
        if (expiresAt != null) {
            if (Instant.now().isAfter(expiresAt)) {
                revoked.remove(hash);
            } else {
                return true;
            }
        }
        return revokedRemotely(hash);
    }

    /**
     * 双写 L2：TTL 取令牌剩余有效期（不写死），保证黑名单条目不会比令牌活得更久。
     * 已过期的令牌直接跳过——此时它本就无法通过签名校验，登记反而是无意义增长。
     */
    private void writeThrough(String hash, Instant expiresAt) {
        if (cacheService == null) {
            return;
        }
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        try {
            cacheService.put(REMOTE_KEY_PREFIX + hash, REMOTE_VALUE, ttl);
        } catch (RuntimeException e) {
            // 不因远端故障阻断登出：L1 已生效，最坏情况是跨实例吊销延迟
            log.warn("登出令牌写入远端黑名单失败，本次仅本实例生效: {}", e.getMessage());
        }
    }

    private boolean revokedRemotely(String hash) {
        if (cacheService == null) {
            return false;
        }
        try {
            return cacheService.get(REMOTE_KEY_PREFIX + hash, String.class) != null;
        } catch (RuntimeException e) {
            log.debug("查询远端黑名单失败，按未吊销处理: {}", e.getMessage());
            return false;
        }
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
