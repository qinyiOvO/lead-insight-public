package com.leadinsight;

import com.leadinsight.module.extract.client.LlmClient;
import com.leadinsight.module.extract.config.LlmProperties;
import com.leadinsight.module.extract.llm.PerUserLlmQuotaGuard;
import com.leadinsight.module.extract.llm.LlmCircuitBreaker;
import com.leadinsight.module.extract.llm.LlmQuotaGuard;
import com.leadinsight.module.extract.llm.RateLimiter;
import com.leadinsight.module.extract.llm.TokenBucketRateLimiter;
import com.leadinsight.module.extract.service.LeadExtractService;
import com.leadinsight.module.lead.service.LeadService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 完整 Spring 上下文装配测试（回归防线）。
 *
 * <p><b>存在意义：</b>本项目其余测试全是纯单元测试（手动 {@code new}），不加载 Spring 上下文——
 * 因此任何 Bean 装配错误（多构造器未标注、缺少依赖、循环依赖等）在纯单测下结构性不可见。
 * 本类用 {@code @SpringBootTest}（默认 {@code webEnvironment = MOCK}，不起真实端口）真实加载上下文，
 * 专门兜住这类问题——曾发生的 {@code PerUserLlmQuotaGuard} 两构造器未标 {@code @Autowired}
 * 导致启动即崩，就是本类要防的复发点。</p>
 *
 * <p><b>铁律遵守：</b>无 key 也必须能启动——故本类<b>绝不</b>断言 api-key 是否存在，只断言 Bean 存在与
 * 模型白名单取值。上下文会读取后端工程根目录的 {@code application-local.yml}，
 * 本类<b>绝不打印</b>其中任何内容。</p>
 */
@SpringBootTest
class LeadInsightApplicationContextTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private LlmProperties llmProperties;

    @Test
    @DisplayName("Spring 上下文可完整加载，且成本红线相关关键 Bean 均装配成功")
    void contextLoadsWithAllCriticalBeans() {
        assertNotNull(context, "Spring 上下文必须能完整加载（装配错误会让本测试直接报错）");

        // 本轮的修复点 + 成本红线相关组件：存在即证明装配成功
        assertInstanceOf(PerUserLlmQuotaGuard.class, context.getBean(LlmQuotaGuard.class),
                "每日调用总量守卫必须装配成功（两构造器需 @Autowired，否则启动即崩）");
        assertInstanceOf(TokenBucketRateLimiter.class, context.getBean(TokenBucketRateLimiter.class),
                "令牌桶限流器必须装配成功");
        assertNotNull(context.getBean(RateLimiter.class), "RateLimiter 接口必须可解析到实现");
        assertNotNull(context.getBean(LlmCircuitBreaker.class), "熔断器必须装配成功");
        assertNotNull(context.getBean(LlmClient.class), "模型客户端必须装配成功");
        assertNotNull(context.getBean(LeadExtractService.class), "抽取编排服务必须装配成功");
        assertNotNull(context.getBean(LeadService.class), "线索服务必须装配成功");
    }

    @Test
    @DisplayName("模型白名单在真实上下文生效：llm.model = deepseek-flash")
    void modelIsWhitelistedFlash() {
        assertEquals("deepseek-flash", llmProperties.getModel(), "上下文中的模型必须为白名单内的 deepseek-flash");
    }

    @Test
    @DisplayName("配额守卫在真实上下文读到配置上限——装配未静默失效（不得退化为 0=不限制）")
    void quotaGuardReadsConfiguredLimit() {
        assertNotNull(llmProperties.getQuota(), "llm.quota 配置必须已绑定");
        assertTrue(llmProperties.getQuota().getDailyLimit() > 0,
                "每日配额上限必须为正数且来自配置（默认 10）；若退化为 0 说明装配取不到配置值，成本闸失效");
    }
}
