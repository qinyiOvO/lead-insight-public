package com.leadinsight.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Collections;

/**
 * Redis 实现（M2）。基于 {@link StringRedisTemplate} + Jackson，值以可读文本落库，
 * 便于用 {@code redis-cli GET} 直接排查与演示。
 *
 * <p><b>与内存实现必须保持一致的三处语义（见 CacheServiceContractTest）：</b></p>
 * <ol>
 *   <li>过期判定：不存在与已过期一律返回 null / false，不抛异常；</li>
 *   <li>{@code putIfAbsent} 必须原子（用 {@code SET ... NX PX} 单命令），不得拆成 EXISTS + SET；</li>
 *   <li>{@code increment} 只在首次建键时设置过期时间，已存在的键<b>不续期</b>——
 *       每次都续期会把「固定窗口计数」变成「最后一次失败后 N 秒」的滚动窗口，
 *       持续重试的账号会被无限期拒绝。</li>
 * </ol>
 *
 * <p><b>取值约定：</b>{@link #get(String)} 返回原始字符串（不做反序列化）；需要对象请用
 * {@link #get(String, Class)}，否则小整数会被 Jackson 反序列化成 Integer，
 * 而内存实现返回 Long——两套实现的类型差异会静默击穿调用方。</p>
 */
@Slf4j
public class RedisCacheService implements CacheService {

    /**
     * 自增 + 首次建键时设置过期时间。
     * 用 Lua 保证「INCR 与 PEXPIRE 之间不会插入别的命令」，避免自增成功却漏设过期导致计数永不过期。
     */
    private static final RedisScript<Long> INCREMENT_WITH_TTL = new DefaultRedisScript<>(
            "local v = redis.call('INCR', KEYS[1]) "
                    + "if v == 1 and tonumber(ARGV[1]) > 0 then "
                    + "redis.call('PEXPIRE', KEYS[1], ARGV[1]) "
                    + "end "
                    + "return v",
            Long.class);

    private final StringRedisTemplate template;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;

    public RedisCacheService(StringRedisTemplate template, ObjectMapper objectMapper, String keyPrefix) {
        this.template = template;
        this.objectMapper = objectMapper;
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
    }

    @Override
    public void put(String key, Object value, Duration ttl) {
        String k = key(key);
        String text = serialize(value);
        long millis = millis(ttl);
        if (millis > 0) {
            template.opsForValue().set(k, text, Duration.ofMillis(millis));
        } else {
            template.opsForValue().set(k, text);
        }
    }

    @Override
    public Object get(String key) {
        return template.opsForValue().get(key(key));
    }

    @Override
    public <T> T get(String key, Class<T> type) {
        String text = template.opsForValue().get(key(key));
        if (text == null) {
            return null;
        }
        if (type == String.class) {
            return type.cast(text);
        }
        try {
            return objectMapper.readValue(text, type);
        } catch (JsonProcessingException e) {
            // 序列化失败属于代码缺陷，不能伪装成「缓存未命中」
            throw new IllegalStateException("缓存值反序列化失败: key=" + key + " targetType=" + type.getSimpleName(), e);
        }
    }

    @Override
    public boolean putIfAbsent(String key, Object value, Duration ttl) {
        String k = key(key);
        String text = serialize(value);
        long millis = millis(ttl);
        Boolean written = millis > 0
                ? template.opsForValue().setIfAbsent(k, text, Duration.ofMillis(millis))
                : template.opsForValue().setIfAbsent(k, text);
        return Boolean.TRUE.equals(written);
    }

    @Override
    public void delete(String key) {
        template.delete(key(key));
    }

    @Override
    public long increment(String key, Duration ttl) {
        Long value = template.execute(INCREMENT_WITH_TTL,
                Collections.singletonList(key(key)),
                String.valueOf(millis(ttl)));
        return value == null ? 0L : value;
    }

    /** 统一加前缀；外部传入的键不含命名空间，避免调用方各自拼前缀导致风格分裂 */
    private String key(String key) {
        return keyPrefix + key;
    }

    /**
     * 字符串原样存（存 "abc" 读出来还是 "abc"），其余类型走 JSON。
     * 若统一 JSON 化，字符串会被存成带引号的 {@code "abc"}，调用方拿到引号属于隐性破坏契约。
     */
    private String serialize(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("缓存值不允许为 null，删除请调用 delete(key)");
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("缓存值序列化失败: type=" + value.getClass().getName(), e);
        }
    }

    /** ttl 为 null/0/负数视为不过期（与内存实现的 Long.MAX_VALUE 语义对齐） */
    private long millis(Duration ttl) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return 0L;
        }
        return ttl.toMillis();
    }
}
