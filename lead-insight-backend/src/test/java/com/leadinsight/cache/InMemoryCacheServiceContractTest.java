package com.leadinsight.cache;

import org.junit.jupiter.api.DisplayName;

/** 内存实现的契约测试：零依赖，任何环境都跑（保证契约本身被持续验证）。 */
@DisplayName("CacheService 契约 - 内存实现")
class InMemoryCacheServiceContractTest extends AbstractCacheServiceContractTest {

    @Override
    protected CacheService createCache() {
        return new InMemoryCacheService();
    }
}
