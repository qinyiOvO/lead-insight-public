package com.leadinsight.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 实现的契约测试。
 *
 * <p><b>默认不执行</b>：本仓库的 223 例基线必须在一台「没装 Redis」的机器上也能全绿，
 * 因此用 {@code @EnabledIfSystemProperty} 显式开关。执行方式：</p>
 * <pre>
 *   mvn test -Dcache.redis.test=true
 * </pre>
 * <p>前提：本机 redis-server 已在 127.0.0.1:6379 运行（见 docs/02-架构/Redis接入方案-M2-v1.0.md §6.1）。</p>
 */
@DisplayName("CacheService 契约 - Redis 实现")
@EnabledIfSystemProperty(named = "cache.redis.test", matches = "true")
class RedisCacheServiceContractTest extends AbstractCacheServiceContractTest {

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate template;

    @BeforeAll
    static void startUp() {
        connectionFactory = new LettuceConnectionFactory("127.0.0.1", 6379);
        connectionFactory.afterPropertiesSet();
        template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Override
    protected CacheService createCache() {
        return new RedisCacheService(template, new ObjectMapper(), "li:");
    }
}
