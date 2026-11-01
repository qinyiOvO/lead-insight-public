package com.leadinsight.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 缓存装配（M2）。全局只有一个 {@link CacheService} Bean，由 {@code app.cache.mode} 决定实现，
 * 业务代码按接口注入，切换实现无需改动任何调用方。
 *
 * <p><b>为什么合并成一个 @Bean 而不是两个 @ConditionalOnProperty：</b>本仓库此前没有任何条件装配先例，
 * 用 {@code havingValue} 比对字符串对大小写敏感（{@code mode: REDIS} 会导致两个分支都不装配、
 * 启动时才报找不到 Bean）。这里改为在 Bean 方法内按枚举分支，配置绑定本身就是宽松的，
 * 且「装配错误」与「连接失败」能在同一处给出可读报错。</p>
 *
 * <p><b>redis 模式下启动期 PING：</b>连不上直接抛异常终止启动（fail-fast）。
 * 运行期 Redis 中途掉线则走 {@link FallbackCacheService} 降级——两者不矛盾：
 * 启动期是「配置错了必须暴露」，运行期是「可用性优先」。</p>
 */
@Slf4j
@Configuration
public class CacheConfig {

    @Bean
    public CacheService cacheService(CacheProperties properties,
                                     ObjectProvider<StringRedisTemplate> templateProvider,
                                     ObjectMapper objectMapper) {
        CacheService inMemory = new InMemoryCacheService();
        CacheProperties.Mode mode = properties.getMode() == null
                ? CacheProperties.Mode.MEMORY
                : properties.getMode();

        if (mode != CacheProperties.Mode.REDIS) {
            log.info("缓存实现 = memory（进程内 ConcurrentHashMap，无外部依赖）；"
                    + "如需 Redis 请设 app.cache.mode=redis 或环境变量 CACHE_MODE=redis");
            return inMemory;
        }

        StringRedisTemplate template = templateProvider.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException(
                    "app.cache.mode=redis，但容器中没有 StringRedisTemplate；"
                            + "请确认已引入 spring-boot-starter-data-redis");
        }
        pingOrFail(template);

        log.info("缓存实现 = redis（{}，key 前缀 = {}）；Redis 不可用时会自动降级为进程内实现",
                describe(template), properties.getKeyPrefix());
        return new FallbackCacheService(
                new RedisCacheService(template, objectMapper, properties.getKeyPrefix()),
                inMemory);
    }

    /** 启动期探活：连不上就抛，不静默退化成内存实现 */
    private void pingOrFail(StringRedisTemplate template) {
        try (RedisConnection connection = template.getConnectionFactory().getConnection()) {
            String pong = connection.ping();
            if (pong == null || !"PONG".equalsIgnoreCase(pong.trim())) {
                throw new IllegalStateException("Redis PING 返回异常: " + pong);
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "app.cache.mode=redis 但无法连接 Redis（" + describe(template) + "）。"
                            + "请确认 redis-server 已启动、端口与 spring.data.redis.* 配置一致；"
                            + "若只想用内存缓存，把 app.cache.mode 改回 memory 即可。", e);
        }
    }

    private String describe(StringRedisTemplate template) {
        var factory = template.getConnectionFactory();
        if (factory instanceof org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory lettuce) {
            return lettuce.getHostName() + ":" + lettuce.getPort() + "/" + lettuce.getDatabase();
        }
        return String.valueOf(factory);
    }
}
