package com.leadinsight.module.extract.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.leadinsight.module.extract.client.LlmCallException;
import com.leadinsight.module.extract.client.LlmChatRequest;
import com.leadinsight.module.extract.client.LlmChatResponse;
import com.leadinsight.module.extract.client.LlmClient;
import com.leadinsight.module.extract.config.LlmProperties;
import com.leadinsight.module.extract.entity.LeadExtractResult;
import com.leadinsight.module.extract.llm.LlmCircuitBreaker;
import com.leadinsight.module.extract.llm.LlmQuotaGuard;
import com.leadinsight.module.extract.llm.RateLimiter;
import com.leadinsight.module.extract.mapper.LeadExtractResultMapper;
import com.leadinsight.module.extract.parser.ExtractedLead;
import com.leadinsight.module.extract.parser.ExtractParseException;
import com.leadinsight.module.extract.parser.ExtractResultParser;
import com.leadinsight.module.extract.prompt.PromptLoader;
import com.leadinsight.module.lead.entity.Lead;
import com.leadinsight.module.lead.enums.CompanyScale;
import com.leadinsight.module.lead.enums.ExtractStatus;
import com.leadinsight.module.lead.enums.IntentCategory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * 抽取编排实现（亮点1 + 亮点4）。
 *
 * <p>流程：无 key / 熔断 → 直接 DEGRADED；否则按「限流令牌 → 调模型 → 解析校验 → 置信度自算」
 * 循环，总尝试 {@code llm.extract.max-attempts}（默认 3，含首次，R-1 裁定）。
 * 解析类失败穷尽 → FAILED（转人工）；模型不可用类失败穷尽 → DEGRADED。
 * 每次真实模型调用落 {@code model_call_log}（无 key 明文，请求侧只落 SHA-256 指纹；
 * 熔断拒绝非真实调用不落，保证记录数与实际调用次数一致）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeadExtractServiceImpl implements LeadExtractService {

    private static final int ERROR_MESSAGE_MAX = 500;
    private static final long BASE_BACKOFF_MS = 500;
    private static final long MAX_BACKOFF_MS = 5000;
    private static final long RAW_RESPONSE_MAX = 1_000_000;

    private final LlmProperties properties;
    private final LlmClient llmClient;
    private final RateLimiter rateLimiter;
    private final LlmCircuitBreaker circuitBreaker;
    private final LlmQuotaGuard quotaGuard;
    private final PromptLoader promptLoader;
    private final ExtractResultParser parser;
    private final ConfidenceCalculator confidenceCalculator;
    private final LeadExtractResultMapper extractResultMapper;
    private final ModelCallLogger modelCallLogger;

    @Override
    public boolean isModelConfigured() {
        return properties.hasKey();
    }

    @Override
    public ExtractOutcome extract(Lead lead, Long operatorId, String clientIp) {
        int maxAttempts = Math.max(1, properties.getExtract().getMaxAttempts());

        if (!properties.hasKey()) {
            return finishWithoutModelCall(lead,
                    "未配置大模型 API Key（DEEPSEEK_API_KEY 为空），本次抽取已降级为待人工处理，线索已正常入库");
        }
        if (!circuitBreaker.allowCall()) {
            // 熔断拒绝并非真实模型调用，不落 model_call_log（对齐走查判据行12/13：
            // 记录数与实际调用次数一致，熔断期内不多记）
            return finishWithoutModelCall(lead,
                    "模型连续失败触发熔断（" + properties.getCircuitBreaker().getOpenSeconds()
                            + " 秒），本次抽取已降级为待人工处理");
        }
        // 成本红线：按账户的每日额度 + 用满封禁。在熔断放行之后、真正调用模型之前检查一次——
        // 未走到这里（无 key / 熔断）不算调用，也就不消耗额度。未获批直接 DEGRADED（不抛异常，
        // 线索照常落库）；额度按「每条线索 1 个」计，重试不再单独计数（上界 maxAttempts-1）。
        LlmQuotaGuard.Result quota = quotaGuard.tryAcquire(operatorId, clientIp);
        if (!quota.granted()) {
            return finishWithoutModelCall(lead, quotaMessage(quota));
        }

        String prompt = promptLoader.renderExtractPrompt(lead.getOriginalText());
        String fingerprint = sha256Hex(prompt);
        int maxTokens = properties.getExtract().getMaxTokens();

        String rawResponse = null;
        String lastError = null;
        boolean lastFailureIsParse = false;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (attempt > 0) {
                backoff(attempt);
            }
            if (!rateLimiter.tryAcquire()) {
                modelCallLogger.record(lead.getId(), null, fingerprint, ModelCallLogger.RESULT_RATE_LIMITED,
                        null, null, 0, attempt, false, "LOCAL_TOKEN_SHORTAGE", "本地限流令牌不足，退避后重试");
                lastError = "本地限流令牌不足";
                lastFailureIsParse = false;
                continue;
            }

            long start = System.currentTimeMillis();
            try {
                LlmChatResponse response = llmClient.chat(new LlmChatRequest(prompt, maxTokens));
                long latencyMs = System.currentTimeMillis() - start;
                rawResponse = response.content();
                try {
                    ExtractedLead parsed = parser.parseAndValidate(rawResponse);
                    circuitBreaker.recordSuccess();
                    modelCallLogger.record(lead.getId(), null, fingerprint, ModelCallLogger.RESULT_SUCCESS,
                            200, response, latencyMs, attempt, false, null, null);
                    return succeed(lead, parsed, rawResponse, response.modelName(), attempt);
                } catch (ExtractParseException pe) {
                    circuitBreaker.recordFailure();
                    modelCallLogger.record(lead.getId(), null, fingerprint, ModelCallLogger.RESULT_PARSE_FAIL,
                            200, response, latencyMs, attempt, false, "PARSE_FAIL", pe.getMessage());
                    lastError = pe.getMessage();
                    lastFailureIsParse = true;
                }
            } catch (LlmCallException ce) {
                long latencyMs = System.currentTimeMillis() - start;
                circuitBreaker.recordFailure();
                modelCallLogger.record(lead.getId(), null, fingerprint, ce.getKind().name(),
                        ce.getHttpStatus(), null, latencyMs, attempt, false, ce.getKind().name(), ce.getMessage());
                lastError = ce.getMessage();
                lastFailureIsParse = false;
            }
        }

        return lastFailureIsParse
                ? failToHuman(lead, rawResponse, maxAttempts - 1, lastError)
                : degrade(lead, rawResponse, maxAttempts - 1, lastError);
    }

    // -------------------------------------------------------------- 终态装配

    private ExtractOutcome succeed(Lead lead, ExtractedLead parsed, String rawResponse,
                                   String modelName, int failedAttempts) {
        applyExtractedFields(lead, parsed);
        ConfidenceCalculator.Confidence confidence =
                confidenceCalculator.assess(parsed, lead.getOriginalText(), failedAttempts);
        lead.setExtractConfidence(confidence.value());
        lead.setConfidenceLevel(confidence.level().name());
        lead.setExtractStatus(ExtractStatus.SUCCESS.name());
        upsertResult(lead.getId(), rawResponse, parser.toJson(parsed),
                "SUCCESS", failedAttempts, null, modelName);
        return new ExtractOutcome(ExtractStatus.SUCCESS,
                "抽取成功，置信度 " + confidence.level() + "（" + confidence.value() + "）");
    }

    /** 解析类失败穷尽：转人工（线索仍在，confidence_level 保持 NULL） */
    private ExtractOutcome failToHuman(Lead lead, String rawResponse, int retryCount, String error) {
        lead.setExtractStatus(ExtractStatus.FAILED.name());
        lead.setExtractConfidence(null);
        lead.setConfidenceLevel(null);
        upsertResult(lead.getId(), rawResponse, null, "FAILED", retryCount, error, properties.getModel());
        return new ExtractOutcome(ExtractStatus.FAILED, "抽取解析失败已转人工：" + error);
    }

    /** 模型不可用类失败穷尽：降级（主流程不受影响，priority_score 由调用方按空字段重算） */
    private ExtractOutcome degrade(Lead lead, String rawResponse, int retryCount, String error) {
        lead.setExtractStatus(ExtractStatus.DEGRADED.name());
        lead.setExtractConfidence(null);
        lead.setConfidenceLevel(null);
        upsertResult(lead.getId(), rawResponse, null, "DEGRADED", retryCount, error, properties.getModel());
        return new ExtractOutcome(ExtractStatus.DEGRADED, "模型不可用已降级：" + error);
    }

    private ExtractOutcome finishWithoutModelCall(Lead lead, String message) {
        lead.setExtractStatus(ExtractStatus.DEGRADED.name());
        lead.setExtractConfidence(null);
        lead.setConfidenceLevel(null);
        upsertResult(lead.getId(), null, null, "DEGRADED", 0, message, null);
        return new ExtractOutcome(ExtractStatus.DEGRADED, message);
    }

    // -------------------------------------------------------------- 内部工具

    /** 抽取字段回填（只回填模型给出的字段，不覆盖调用方已设置的状态类字段） */
    private void applyExtractedFields(Lead lead, ExtractedLead parsed) {
        lead.setCustomerName(parsed.getCustomerName());
        lead.setCompanyName(parsed.getCompanyName());
        lead.setContactInfo(parsed.getContactInfo());
        lead.setIntentCategory(IntentCategory.fromCode(parsed.getIntentCategory()).name());
        lead.setDemandSummary(parsed.getDemandSummary());
        lead.setBudgetAmount(parsed.getBudgetAmount());
        lead.setBudgetText(parsed.getBudgetText());
        lead.setExpectedTime(parsed.getExpectedTime());
        lead.setExpectedTimeText(parsed.getExpectedTimeText());
        CompanyScale scale = CompanyScale.fromCode(parsed.getCompanyScale());
        lead.setCompanyScale(scale == null ? null : scale.name());
        lead.setKeywordsHit(parsed.getKeywordsHit());
    }

    private void upsertResult(Long leadId, String rawResponse, String parsedJson, String parseStatus,
                              int retryCount, String errorMessage, String modelName) {
        try {
            LeadExtractResult existing = extractResultMapper.selectOne(Wrappers.<LeadExtractResult>lambdaQuery()
                    .eq(LeadExtractResult::getLeadId, leadId));
            LeadExtractResult row = existing == null ? new LeadExtractResult() : existing;
            row.setLeadId(leadId);
            row.setRawResponse(truncate(rawResponse, RAW_RESPONSE_MAX));
            row.setParsedJson(parsedJson);
            row.setParseStatus(parseStatus);
            row.setRetryCount(retryCount);
            row.setErrorMessage(truncate(errorMessage, ERROR_MESSAGE_MAX));
            row.setParserVersion(ExtractResultParser.PARSER_VERSION);
            row.setPromptVersion(PromptLoader.PROMPT_VERSION);
            row.setModelName(modelName);
            if (existing == null) {
                extractResultMapper.insert(row);
            } else {
                extractResultMapper.updateById(row);
            }
        } catch (Exception e) {
            log.warn("lead_extract_result 落库失败（不影响主流程）: {}", e.getMessage());
        }
    }

    /**
     * 未获额度时的可读文案。三种情形分别说清「为什么没抽」与「怎么办」，
     * 其中封禁情形必须给出解禁时间——否则用户只会以为系统坏了。
     */
    private String quotaMessage(LlmQuotaGuard.Result quota) {
        int limit = properties.getQuota().getDailyLimit();
        if (quota.decision() == LlmQuotaGuard.Decision.BANNED && quota.bannedUntil() != null) {
            String until = quota.bannedUntil().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            // 必须区分「自己的账户被用满」与「被同 IP 的别人牵连」：
            // 后者账户一次都没用过，若提示「你的额度已用满」纯属误导，用户只会去找客服。
            if (quota.banTarget() == LlmQuotaGuard.BanTarget.IP) {
                return "当前来源 IP 已触发单日抽取额度上限（每账户每日 " + limit + " 次），按成本红线已暂停该 IP 消耗模型额度至 "
                        + until + "，期间新建/重抽一律降级为待人工处理，线索仍可正常入库与跟进";
            }
            return "该账户当日抽取额度已用满（每账户每日 " + limit + " 次），按成本红线已停止其消耗模型额度至 "
                    + until + "，期间新建/重抽一律降级为待人工处理，线索仍可正常入库与跟进";
        }
        return "该账户今日抽取额度已用尽（每账户每日 " + limit + " 次，应用层成本红线），"
                + "本次抽取已降级为待人工处理，线索已正常入库";
    }

    /** 指数退避（限流/失败后），上限 5s，避免拖垮同步请求 */
    private void backoff(int attempt) {
        long ms = Math.min(BASE_BACKOFF_MS * (1L << Math.min(attempt - 1, 4)), MAX_BACKOFF_MS);
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String truncate(String value, long max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, (int) max);
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }
}
