package com.leadinsight.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具（jjwt 0.12.6）。
 *
 * <p>0.12.x API 与 0.9.x 完全不同：解析必须用
 * {@code Jwts.parser().verifyWith(key).build().parseSignedClaims(token)}，
 * 生成用 {@code Jwts.builder()...signWith(key).compact()}。
 * HS256 密钥长度必须 >= 32 字节（256 bit）。</p>
 */
@Slf4j
@Component
public class JwtUtil {

    public static final String CLAIM_USER_ID = "uid";
    public static final String CLAIM_USERNAME = "username";
    public static final String CLAIM_ROLE = "role";

    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey secretKey;

    @Getter
    private final long expireSeconds;

    public JwtUtil(@Value("${jwt.secret:}") String secret,
                   @Value("${jwt.expire-seconds:7200}") long expireSeconds) {
        this.expireSeconds = expireSeconds > 0 ? expireSeconds : 7200L;
        this.secretKey = buildKey(secret);
    }

    private SecretKey buildKey(String secret) {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            // 不因缺 key 直接崩溃（SPEC §3：无外部密钥也必须可运行），
            // 但明确告警：随机密钥在重启后失效，生产必须配置 jwt.secret。
            log.warn("jwt.secret 未配置或长度不足 {} 字节，已退化为本次进程随机密钥；"
                    + "重启后已签发令牌将全部失效。生产环境必须通过环境变量 JWT_SECRET 配置。", MIN_SECRET_BYTES);
            return Jwts.SIG.HS256.key().build();
        }
        return Keys.hmacShaKeyFor(bytes);
    }

    /** 生成访问令牌 */
    public String generateToken(Long userId, String username, String role) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expireSeconds * 1000L);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_USER_ID, userId)
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_ROLE, role)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 解析并校验令牌。
     *
     * @throws io.jsonwebtoken.ExpiredJwtException 令牌过期
     * @throws JwtException                        签名错误 / 格式非法
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long extractUserId(Claims claims) {
        Object value = claims.get(CLAIM_USER_ID);
        if (value instanceof Number number) {
            return number.longValue();
        }
        String subject = claims.getSubject();
        return subject == null ? null : Long.valueOf(subject);
    }

    public String extractUsername(Claims claims) {
        return claims.get(CLAIM_USERNAME, String.class);
    }

    public String extractRole(Claims claims) {
        return claims.get(CLAIM_ROLE, String.class);
    }
}
