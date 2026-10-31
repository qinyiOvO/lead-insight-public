package com.leadinsight.module.extract.parser;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leadinsight.common.util.TextUtils;
import com.leadinsight.module.lead.enums.CompanyScale;
import com.leadinsight.module.lead.enums.IntentCategory;
import org.springframework.stereotype.Component;

/**
 * 抽取结果解析 + 校验（T2-02，ADR-004 决策 1）。
 *
 * <p>校验规则（任一不满足即抛 {@link ExtractParseException} 触发重试）：</p>
 * <ol>
 *   <li>内容非空（官方已知「JSON Output 偶发返回空内容」，空内容按失败处理）；</li>
 *   <li>必须是合法 JSON 对象；JSON 字面量 {@code null}（Jackson 会静默返回 null）按失败处理；</li>
 *   <li>intentCategory 必填且必须命中五类枚举之一（枚举合法性是亮点1验收点）；</li>
 *   <li>budgetAmount 若给必须是 ≥0 数字；expectedTime 若给必须是 yyyy-MM-dd；</li>
 *   <li>companyScale 若给必须命中枚举。</li>
 * </ol>
 * 其余字段缺失不判失败——字段完整性影响置信度档位（T2-03），不是解析失败。
 */
@Component
public class ExtractResultParser {

    /** 与 ADR-004 / lead_extract_result.parser_version 对齐 */
    public static final String PARSER_VERSION = "v1";

    public static final int DEMAND_SUMMARY_MAX = 500;
    public static final int TEXT_MAX = 150;

    private final ObjectMapper mapper;

    public ExtractResultParser() {
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /** 解析并校验；成功返回清洗后的结构化对象 */
    public ExtractedLead parseAndValidate(String raw) {
        if (TextUtils.isBlank(raw)) {
            throw new ExtractParseException("模型返回内容为空");
        }
        ExtractedLead extracted = readObject(raw.trim());
        sanitize(extracted);
        validate(extracted);
        return extracted;
    }

    /** 把校验通过的对象还原为 JSON 字符串（落 lead_extract_result.parsed_json） */
    public String toJson(ExtractedLead extracted) {
        try {
            return mapper.writeValueAsString(extracted);
        } catch (Exception e) {
            throw new ExtractParseException("校验通过的对象序列化失败", e);
        }
    }

    private ExtractedLead readObject(String raw) {
        ExtractedLead parsed;
        try {
            parsed = mapper.readValue(raw, ExtractedLead.class);
        } catch (Exception e) {
            throw new ExtractParseException("返回内容不是合法 JSON 对象: " + rootMessage(e), e);
        }
        // Jackson 遇到 JSON 字面量 null（模型偶发真的回一个 null）不抛异常而是返回 null，
        // 若放行会让 sanitize 空指针穿透到调用方。这里统一折算为 ExtractParseException，
        // 交由上层「解析类失败穷尽 → FAILED 转人工」处理，兑现「extract 永不抛异常」契约。
        if (parsed == null) {
            throw new ExtractParseException("模型返回内容为 JSON null，不是合法 JSON 对象");
        }
        return parsed;
    }

    private void sanitize(ExtractedLead lead) {
        lead.setCustomerName(TextUtils.trimToNull(lead.getCustomerName()));
        lead.setCompanyName(TextUtils.trimToNull(lead.getCompanyName()));
        lead.setContactInfo(TextUtils.trimToNull(lead.getContactInfo()));
        lead.setIntentCategory(TextUtils.trimToNull(lead.getIntentCategory()));
        lead.setDemandSummary(TextUtils.trimToNull(lead.getDemandSummary()));
        lead.setBudgetText(TextUtils.trimToNull(lead.getBudgetText()));
        lead.setExpectedTimeText(TextUtils.trimToNull(lead.getExpectedTimeText()));
        lead.setCompanyScale(TextUtils.trimToNull(lead.getCompanyScale()));
        lead.setKeywordsHit(TextUtils.trimToNull(lead.getKeywordsHit()));
        if (lead.getDemandSummary() != null && lead.getDemandSummary().length() > DEMAND_SUMMARY_MAX) {
            lead.setDemandSummary(lead.getDemandSummary().substring(0, DEMAND_SUMMARY_MAX));
        }
        if (lead.getBudgetText() != null && lead.getBudgetText().length() > TEXT_MAX) {
            lead.setBudgetText(lead.getBudgetText().substring(0, TEXT_MAX));
        }
    }

    private void validate(ExtractedLead lead) {
        if (lead.getIntentCategory() == null) {
            throw new ExtractParseException("intentCategory 缺失");
        }
        if (IntentCategory.fromCode(lead.getIntentCategory()) == null) {
            throw new ExtractParseException("intentCategory 非法: " + lead.getIntentCategory());
        }
        if (lead.getBudgetAmount() != null && lead.getBudgetAmount().signum() < 0) {
            throw new ExtractParseException("budgetAmount 不能为负数");
        }
        if (lead.getCompanyScale() != null && CompanyScale.fromCode(lead.getCompanyScale()) == null) {
            throw new ExtractParseException("companyScale 非法: " + lead.getCompanyScale());
        }
    }

    private String rootMessage(Exception e) {
        String msg = e.getMessage();
        return msg == null ? e.getClass().getSimpleName() : msg;
    }
}
