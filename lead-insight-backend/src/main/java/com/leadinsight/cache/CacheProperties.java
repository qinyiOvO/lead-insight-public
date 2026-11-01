package com.leadinsight.cache;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 缓存配置（M2）。开关只有一个：{@code app.cache.mode}。
 *
 * <p><b>memory</b>（默认）：进程内实现，零外部依赖，答辩/离线环境可直接跑。
 * <b>redis</b>：Redis 实现，启动期做一次 PING 探活，连不上直接启动失败（fail-fast）——
 * 静默退回内存会让「以为在用 Redis 其实没用」成为最难排查的故障。</p>
 *
 * <p>连接参数走 Spring Boot 标准的 {@code spring.data.redis.*}，此处不重复定义。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.cache")
public class CacheProperties {

    /** 缓存实现类型 */
    public enum Mode {
        /** 进程内 ConcurrentHashMap（默认） */
        MEMORY,
        /** 外部 Redis */
        REDIS
    }

    /** 实现类型，默认内存 */
    private Mode mode = Mode.MEMORY;

    /**
     * 键前缀。所有经 {@link CacheService} 写入的键都会自动加上此前缀，
     * 用于多项目共用一个 Redis 实例时隔离命名空间。
     */
    private String keyPrefix = "li:";
}
