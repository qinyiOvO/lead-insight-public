package com.leadinsight.module.extract.service;

import com.leadinsight.module.extract.client.LlmCallException;
import com.leadinsight.module.extract.client.LlmChatRequest;
import com.leadinsight.module.extract.client.LlmChatResponse;
import com.leadinsight.module.extract.client.LlmClient;
import com.leadinsight.module.extract.config.LlmProperties;
import com.leadinsight.module.extract.entity.LeadExtractResult;
import com.leadinsight.module.extract.llm.PerUserLlmQuotaGuard;
import com.leadinsight.module.extract.llm.QuotaBanStore;
import com.leadinsight.module.extract.llm.LlmCircuitBreaker;
import com.leadinsight.module.extract.llm.TokenBucketRateLimiter;
import com.leadinsight.module.extract.mapper.LeadExtractResultMapper;
import com.leadinsight.module.extract.parser.ExtractResultParser;
import com.leadinsight.module.extract.prompt.PromptLoader;
import com.leadinsight.module.lead.entity.Lead;
import com.leadinsight.module.lead.enums.ExtractStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 抽取编排管线单测（T2-02/03/04）：无 key 降级、熔断降级、重试计数、解析失败转人工、
 * 模型不可用降级、本地限流降级。LlmClient 用 Mockito 打桩，不触外网、不需要真实 key。
 */
class LeadExtractPipelineTest {

    private static final String ORIGINAL =
            "客户：广州振华五金制品有限公司 张三，计划采购304不锈钢管500米，预算60万元，希望7天内拿到正式报价并签订合同。";
    private static final String GOOD_JSON = """
            {"customerName":"张三","companyName":"广州振华五金制品有限公司","intentCategory":"QUOTE",
             "demandSummary":"计划采购304不锈钢管500米","budgetAmount":600000,
             "expectedTime":"2026-10-08","companyScale":"LARGE"}
            """;

    /** 本测试的操作用户（额度按账户计，单测固定一个） */
    private static final long OPERATOR = 1L;

    /** 不做封禁的存储桩：管道测试只关心「有/无额度」，封禁行为由 PerUserLlmQuotaGuardTest 覆盖 */
    private static final QuotaBanStore NO_BAN = new QuotaBanStore() {
        @Override
        public java.util.Optional<java.time.LocalDateTime> activeBanUntil(String banKey,
                                                                         java.time.LocalDateTime now) {
            return java.util.Optional.empty();
        }

        @Override
        public void ban(String banKey, String subjectType, String subjectValue,
                        java.time.LocalDateTime until, int dailyUsed, String reason, String triggerIp) {
            // 桩：不落库
        }

        @Override
        public int countBannedAccountsFromIp(String ip, java.time.LocalDateTime now) {
            return 0; // 桩：永不封 IP
        }

        @Override
        public void clear(String banKey) {
            // 桩：无记录可清
        }
    };

    /** 可读写的内存封禁存储（验证「封禁落库 + 跨账户按 IP 生效」用，取代 NO_BAN 桩） */
    private static QuotaBanStore mutableStore(java.util.Map<String, java.time.LocalDateTime> bans) {
        return new QuotaBanStore() {
            @Override
            public java.util.Optional<java.time.LocalDateTime> activeBanUntil(String banKey,
                                                                             java.time.LocalDateTime now) {
                return java.util.Optional.ofNullable(bans.get(banKey)).filter(t -> t.isAfter(now));
            }

            @Override
            public void ban(String banKey, String subjectType, String subjectValue,
                            java.time.LocalDateTime until, int dailyUsed, String reason, String triggerIp) {
                bans.put(banKey, until);
            }

            @Override
            public int countBannedAccountsFromIp(String ip, java.time.LocalDateTime now) {
                return 1; // 桩：视为「已达阈值」，让本类专注验证降级文案而非阈值策略
            }

            @Override
            public void clear(String banKey) {
                bans.remove(banKey);
            }
        };
    }

    private LlmProperties properties;
    private LlmClient llmClient;
    private LeadExtractResultMapper extractResultMapper;
    private ModelCallLogger modelCallLogger;
    private LlmCircuitBreaker breaker;
    private TokenBucketRateLimiter rateLimiter;
    private PerUserLlmQuotaGuard quotaGuard;
    private LeadExtractServiceImpl service;
    private Lead lead;

    @BeforeEach
    void setUp() {
        // 额度计数是静态的（防止多实例放大额度），同一 JVM 里跨测试类共享 → 必须显式复位，
        // 否则 PerUserLlmQuotaGuardTest 用光的账户 1 额度会让本类的第一条抽取就判超额。
        PerUserLlmQuotaGuard.resetInMemoryState();
        properties = new LlmProperties();
        llmClient = mock(LlmClient.class);
        extractResultMapper = mock(LeadExtractResultMapper.class);
        modelCallLogger = mock(ModelCallLogger.class);
        breaker = new LlmCircuitBreaker(properties);
        rateLimiter = new TokenBucketRateLimiter(properties);
        quotaGuard = new PerUserLlmQuotaGuard(properties, NO_BAN);
        service = new LeadExtractServiceImpl(properties, llmClient, rateLimiter, breaker, quotaGuard,
                new PromptLoader(), new ExtractResultParser(), new ConfidenceCalculator(),
                extractResultMapper, modelCallLogger);
        lead = new Lead();
        lead.setId(1L);
        lead.setOriginalText(ORIGINAL);
    }

    private static LlmChatResponse resp(String content) {
        return new LlmChatResponse(content, 10, 20, 30, "deepseek-flash");
    }

    @Test
    @DisplayName("无 key：DEGRADED、不调用模型、confidence_level 为 NULL、可读文案落 error_message")
    void noKeyDegradesWithoutCallingModel() {
        ExtractOutcome outcome = service.extract(lead, OPERATOR, null);

        assertEquals(ExtractStatus.DEGRADED, outcome.status());
        assertEquals("DEGRADED", lead.getExtractStatus());
        assertNull(lead.getConfidenceLevel(), "未抽取记录 confidence_level 必须为 NULL（R-8 修订）");
        assertNull(lead.getExtractConfidence());
        verify(llmClient, never()).chat(any());

        ArgumentCaptor<LeadExtractResult> captor = ArgumentCaptor.forClass(LeadExtractResult.class);
        verify(extractResultMapper).insert(captor.capture());
        assertEquals("DEGRADED", captor.getValue().getParseStatus());
        assertTrue(captor.getValue().getErrorMessage().contains("未配置大模型 API Key"));
        verify(modelCallLogger, never()).record(any(), any(), any(), any(), any(), any(),
                anyLong(), anyInt(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("熔断打开期：DEGRADED 且不调用模型；熔断拒绝非真实调用，不落 model_call_log（判据行12/13）")
    void breakerOpenDegrades() {
        properties.setApiKey("present");
        for (int i = 0; i < 5; i++) {
            breaker.recordFailure();
        }

        ExtractOutcome outcome = service.extract(lead, OPERATOR, null);

        assertEquals(ExtractStatus.DEGRADED, outcome.status());
        verify(llmClient, never()).chat(any());
        verify(modelCallLogger, never()).record(any(), any(), any(), any(), any(), any(),
                anyLong(), anyInt(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("行12：连续失败达阈值触发熔断——之后新线索不再产生 model_call_log 记录，且秒回 DEGRADED")
    void circuitOpenAfterThresholdStopsLoggingAndReturnsFast() {
        properties.setApiKey("present");
        properties.getCircuitBreaker().setFailureThreshold(5);
        breaker = new LlmCircuitBreaker(properties);
        service = new LeadExtractServiceImpl(properties, llmClient, rateLimiter, breaker, quotaGuard,
                new PromptLoader(), new ExtractResultParser(), new ConfidenceCalculator(),
                extractResultMapper, modelCallLogger);
        when(llmClient.chat(any(LlmChatRequest.class)))
                .thenThrow(new LlmCallException(LlmCallException.FailureKind.TIMEOUT, null, "模型调用超时"));

        Lead lead2 = new Lead();
        lead2.setId(2L);
        lead2.setOriginalText(ORIGINAL);
        service.extract(lead, OPERATOR, null);   // 尝试1-3失败，连续失败计数=3，熔断未开
        service.extract(lead2, OPERATOR, null);  // 尝试4-6失败，第5次达到阈值，熔断打开

        long start = System.currentTimeMillis();
        Lead lead3 = new Lead();
        lead3.setId(3L);
        lead3.setOriginalText(ORIGINAL);
        ExtractOutcome outcome = service.extract(lead3, OPERATOR, null);
        long elapsed = System.currentTimeMillis() - start;

        // ① 达阈值后：不再调用模型，也不产生新 model_call_log 记录（总数停在 6 = 实际调用 6 次）
        assertEquals(ExtractStatus.DEGRADED, outcome.status());
        assertEquals("DEGRADED", lead3.getExtractStatus());
        assertNull(lead3.getConfidenceLevel());
        verify(llmClient, times(6)).chat(any(LlmChatRequest.class));
        verify(modelCallLogger, times(6)).record(any(), any(), any(), any(), any(), any(),
                anyLong(), anyInt(), anyBoolean(), any(), any());
        verify(modelCallLogger, never()).record(any(), any(), any(), any(), any(), any(),
                anyLong(), anyInt(), anyBoolean(), eq("CIRCUIT_OPEN"), any());

        // ② 熔断期内：不经退避重试，快速返回
        assertTrue(elapsed < 500, "熔断期内应快速返回，实际耗时 " + elapsed + "ms");
    }

    @Test
    @DisplayName("首次成功：SUCCESS、字段回填、置信度 HIGH 0.9000、retry_count=0")
    void successFirstTry() {
        properties.setApiKey("present");
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(resp(GOOD_JSON));

        ExtractOutcome outcome = service.extract(lead, OPERATOR, null);

        assertEquals(ExtractStatus.SUCCESS, outcome.status());
        assertEquals("张三", lead.getCustomerName());
        assertEquals("QUOTE", lead.getIntentCategory());
        assertEquals(0, new BigDecimal("0.9000").compareTo(lead.getExtractConfidence()));
        assertEquals("HIGH", lead.getConfidenceLevel());

        ArgumentCaptor<LeadExtractResult> captor = ArgumentCaptor.forClass(LeadExtractResult.class);
        verify(extractResultMapper).insert(captor.capture());
        assertEquals("SUCCESS", captor.getValue().getParseStatus());
        assertEquals(0, captor.getValue().getRetryCount());
        verify(modelCallLogger).record(any(), any(), any(), any(), any(), any(),
                anyLong(), anyInt(), eq(false), any(), any());
    }

    @Test
    @DisplayName("High-1 端到端复现：LLM 返回 JSON 字面量 null → extract() 不抛异常，终态 FAILED 转人工")
    void jsonNullLiteralDoesNotLeakNpe() {
        properties.setApiKey("present");
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(resp("null"));

        // 修复前：readObject 返回 null → sanitize 空指针 → NPE 穿透 extract()（契约「永不抛异常」被破坏）
        // 修复后：折算为 ExtractParseException → 解析类失败穷尽 → FAILED 转人工，主流程不受影响
        ExtractOutcome outcome = assertDoesNotThrow(() -> service.extract(lead, OPERATOR, null),
                "JSON null 必须降级为终态，绝不能抛 NPE 穿透");

        assertEquals(ExtractStatus.FAILED, outcome.status());
        assertEquals("FAILED", lead.getExtractStatus());
        assertNull(lead.getConfidenceLevel(), "转人工不得写入 confidence_level（保持 NULL）");
        assertNull(lead.getExtractConfidence());
        verify(llmClient, times(3)).chat(any(LlmChatRequest.class));

        // 每次尝试各留一条 PARSE_FAIL 调用日志（3 次尝试 = 首次 + 2 次重试）
        verify(modelCallLogger, times(3)).record(any(), any(), any(),
                eq(ModelCallLogger.RESULT_PARSE_FAIL), any(), any(),
                anyLong(), anyInt(), eq(false), eq("PARSE_FAIL"), any());

        ArgumentCaptor<LeadExtractResult> captor = ArgumentCaptor.forClass(LeadExtractResult.class);
        verify(extractResultMapper).insert(captor.capture());
        assertEquals("FAILED", captor.getValue().getParseStatus());
        assertEquals(2, captor.getValue().getRetryCount());
        assertNotNull(captor.getValue().getErrorMessage());
    }

    @Test
    @DisplayName("解析失败重试 2 次仍失败 → FAILED 转人工，retry_count=2（总尝试 3 次，AC-15）")
    void parseFailureExhaustedBecomesFailed() {
        properties.setApiKey("present");
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(resp("这不是 json"));

        ExtractOutcome outcome = service.extract(lead, OPERATOR, null);

        assertEquals(ExtractStatus.FAILED, outcome.status());
        assertEquals("FAILED", lead.getExtractStatus());
        assertNull(lead.getConfidenceLevel());
        verify(llmClient, times(3)).chat(any(LlmChatRequest.class));

        ArgumentCaptor<LeadExtractResult> captor = ArgumentCaptor.forClass(LeadExtractResult.class);
        verify(extractResultMapper).insert(captor.capture());
        assertEquals("FAILED", captor.getValue().getParseStatus());
        assertEquals(2, captor.getValue().getRetryCount());
        // PM 行6补充：失败原因必须落库（界面"已转人工"的文案来源），不得为空
        assertNotNull(captor.getValue().getErrorMessage());
    }

    @Test
    @DisplayName("模型不可用（超时）重试穷尽 → DEGRADED（与解析失败终态区分）")
    void callFailureExhaustedBecomesDegraded() {
        properties.setApiKey("present");
        when(llmClient.chat(any(LlmChatRequest.class)))
                .thenThrow(new LlmCallException(LlmCallException.FailureKind.TIMEOUT, null, "模型调用超时"));

        ExtractOutcome outcome = service.extract(lead, OPERATOR, null);

        assertEquals(ExtractStatus.DEGRADED, outcome.status());
        verify(llmClient, times(3)).chat(any(LlmChatRequest.class));
        ArgumentCaptor<LeadExtractResult> captor = ArgumentCaptor.forClass(LeadExtractResult.class);
        verify(extractResultMapper).insert(captor.capture());
        assertEquals("DEGRADED", captor.getValue().getParseStatus());
        assertEquals(2, captor.getValue().getRetryCount());
    }

    @Test
    @DisplayName("行8：远端限流（429）退避重试穷尽 → DEGRADED，每次重试各留一条 model_call_log")
    void remoteRateLimitedExhaustedBecomesDegraded() {
        properties.setApiKey("present");
        when(llmClient.chat(any(LlmChatRequest.class)))
                .thenThrow(new LlmCallException(LlmCallException.FailureKind.RATE_LIMITED, 429, "请求过于频繁，请稍后重试"));

        long start = System.currentTimeMillis();
        ExtractOutcome outcome = service.extract(lead, OPERATOR, null);
        long elapsed = System.currentTimeMillis() - start;

        // ① 退避重试证据：3 次尝试 = 首次 + 2 次重试，两次退避（500ms + 1000ms）被真实执行
        assertEquals(ExtractStatus.DEGRADED, outcome.status());
        assertEquals("DEGRADED", lead.getExtractStatus());
        assertNull(lead.getConfidenceLevel());
        verify(llmClient, times(3)).chat(any(LlmChatRequest.class));
        assertTrue(elapsed >= 1400, "退避（500ms+1000ms）应真实执行，实际耗时 " + elapsed + "ms");

        // ② 每次重试各留一条 model_call_log：结果 RATE_LIMITED、http_status=429、degraded=0
        verify(modelCallLogger, times(3)).record(any(), any(), any(),
                eq(ModelCallLogger.RESULT_RATE_LIMITED), eq(429), any(),
                anyLong(), anyInt(), eq(false), any(), any());

        // ③ 终态落 lead_extract_result：DEGRADED、retry_count=2
        ArgumentCaptor<LeadExtractResult> captor = ArgumentCaptor.forClass(LeadExtractResult.class);
        verify(extractResultMapper).insert(captor.capture());
        assertEquals("DEGRADED", captor.getValue().getParseStatus());
        assertEquals(2, captor.getValue().getRetryCount());
    }

    @Test
    @DisplayName("本地令牌不足：不调用模型，退避穷尽后 DEGRADED，限流事件落日志")
    void localRateLimitDegrades() {
        properties.setApiKey("present");
        properties.getRateLimit().setCapacity(1);
        properties.getRateLimit().setRefillPerSecond(0.0001);
        rateLimiter = new TokenBucketRateLimiter(properties);
        assertTrue(rateLimiter.tryAcquire(), "预耗尽唯一令牌");
        service = new LeadExtractServiceImpl(properties, llmClient, rateLimiter, breaker, quotaGuard,
                new PromptLoader(), new ExtractResultParser(), new ConfidenceCalculator(),
                extractResultMapper, modelCallLogger);

        ExtractOutcome outcome = service.extract(lead, OPERATOR, null);

        assertEquals(ExtractStatus.DEGRADED, outcome.status());
        verify(llmClient, never()).chat(any());
        verify(modelCallLogger, times(3)).record(any(), any(), any(),
                eq(ModelCallLogger.RESULT_RATE_LIMITED), any(), any(),
                anyLong(), anyInt(), eq(false), any(), any());
    }

    @Test
    @DisplayName("日额度用尽（未开封禁）：不调用模型、直接 DEGRADED、线索终态落库（成本红线，永不抛异常）")
    void dailyQuotaExhaustedDegrades() {
        properties.setApiKey("present");
        properties.getQuota().setDailyLimit(1);
        properties.getQuota().setBanDays(0);   // 本用例只验「超额即降级」，封禁分支由下一个用例覆盖
        quotaGuard = new PerUserLlmQuotaGuard(properties, NO_BAN);
        service = new LeadExtractServiceImpl(properties, llmClient, rateLimiter, breaker, quotaGuard,
                new PromptLoader(), new ExtractResultParser(), new ConfidenceCalculator(),
                extractResultMapper, modelCallLogger);
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(resp(GOOD_JSON));

        // 第一条：配额充足 → 正常抽取成功，消耗掉当天唯一 1 个配额
        ExtractOutcome first = service.extract(lead, OPERATOR, null);
        assertEquals(ExtractStatus.SUCCESS, first.status());

        // 第二条：配额已用尽 → DEGRADED，且不再调用模型（没真正调用就不计数）
        Lead second = new Lead();
        second.setId(2L);
        second.setOriginalText(ORIGINAL);
        ExtractOutcome outcome = service.extract(second, OPERATOR, null);

        assertEquals(ExtractStatus.DEGRADED, outcome.status());
        assertEquals("DEGRADED", second.getExtractStatus());
        assertNull(second.getConfidenceLevel(), "降级记录 confidence_level 必须为 NULL");
        verify(llmClient, times(1)).chat(any(LlmChatRequest.class));

        ArgumentCaptor<LeadExtractResult> captor = ArgumentCaptor.forClass(LeadExtractResult.class);
        verify(extractResultMapper, times(2)).insert(captor.capture());
        LeadExtractResult secondRow = captor.getAllValues().get(1);
        assertEquals("DEGRADED", secondRow.getParseStatus());
        assertTrue(secondRow.getErrorMessage().contains("额度已用尽"),
                "降级文案必须说明是额度用尽（用户能据此判断不是系统故障）");
    }

    @Test
    @DisplayName("额度用满即封：降级文案必须给出解禁时间，否则用户以为是系统坏了")
    void dailyQuotaUsedUpBansWithReadableDeadline() {
        properties.setApiKey("present");
        properties.getQuota().setDailyLimit(1);
        properties.getQuota().setBanDays(7);   // 默认口径：用满即封 7 天
        // 用真实存储的内存实现验证「第二次走封禁分支」（不是 NO_BAN 桩）
        java.util.Map<String, java.time.LocalDateTime> bans = new java.util.HashMap<>();
        quotaGuard = new PerUserLlmQuotaGuard(properties, mutableStore(bans));
        service = new LeadExtractServiceImpl(properties, llmClient, rateLimiter, breaker, quotaGuard,
                new PromptLoader(), new ExtractResultParser(), new ConfidenceCalculator(),
                extractResultMapper, modelCallLogger);
        when(llmClient.chat(any(LlmChatRequest.class))).thenReturn(resp(GOOD_JSON));

        assertEquals(ExtractStatus.SUCCESS, service.extract(lead, OPERATOR, null).status());

        Lead second = new Lead();
        second.setId(3L);
        second.setOriginalText(ORIGINAL);
        ExtractOutcome outcome = service.extract(second, OPERATOR, null);

        assertEquals(ExtractStatus.DEGRADED, outcome.status());
        assertTrue(bans.containsKey(com.leadinsight.module.extract.llm.QuotaBanKey.ofUser(OPERATOR)),
                "用满即封：账户封禁必须落库");
        assertTrue(outcome.message().contains("已停止其消耗模型额度至"),
                "封禁文案必须给出解禁时间，实际文案=" + outcome.message());
        verify(llmClient, times(1)).chat(any(LlmChatRequest.class));
    }

    @Test
    @DisplayName("被同 IP 牵连封禁：文案必须指向 IP，不能诬告「你的账户已用满」（账户一次都没用过）")
    void ipBanMessagePointsAtIpNotTheInnocentAccount() {
        properties.setApiKey("present");
        properties.getQuota().setDailyLimit(10);
        properties.getQuota().setBanDays(7);
        // 只有 IP 被封（模拟同一出口 IP 上另一个账户把额度刷满），本账户额度充足
        java.util.Map<String, java.time.LocalDateTime> bans = new java.util.HashMap<>();
        bans.put(com.leadinsight.module.extract.llm.QuotaBanKey.ofIp("203.0.113.7"),
                java.time.LocalDateTime.now().plusDays(6));
        quotaGuard = new PerUserLlmQuotaGuard(properties, mutableStore(bans));
        service = new LeadExtractServiceImpl(properties, llmClient, rateLimiter, breaker, quotaGuard,
                new PromptLoader(), new ExtractResultParser(), new ConfidenceCalculator(),
                extractResultMapper, modelCallLogger);

        ExtractOutcome outcome = service.extract(lead, OPERATOR, "203.0.113.7");

        assertEquals(ExtractStatus.DEGRADED, outcome.status());
        verify(llmClient, never()).chat(any());
        assertTrue(outcome.message().contains("来源 IP"),
                "被封的是 IP，文案必须说是来源 IP，实际文案=" + outcome.message());
        assertTrue(outcome.message().contains("已暂停该 IP"),
                "文案要说清封的是 IP，实际文案=" + outcome.message());
        assertTrue(!outcome.message().contains("该账户当日抽取额度已用满"),
                "账户一次都没用，不能说它额度已用满——用户会去找客服，实际文案=" + outcome.message());
    }
}
