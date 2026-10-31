package com.leadinsight.module.lead.service.impl;

import com.leadinsight.common.util.TextUtils;
import com.leadinsight.config.ScoringProperties;
import com.leadinsight.module.lead.entity.Lead;
import com.leadinsight.module.lead.enums.CompanyScale;
import com.leadinsight.module.lead.enums.IntentCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 评分规则计算器（纯计算，无数据库访问）。
 * 5 条规则：INTENT(40) / BUDGET(20) / URGENCY(20) / COMPANY(10) / KEYWORD(10)。
 */
@Component
@RequiredArgsConstructor
public class ScoringRuleCalculator {

    private static final String RULE_INTENT = "INTENT";
    private static final String RULE_BUDGET = "BUDGET";
    private static final String RULE_URGENCY = "URGENCY";
    private static final String RULE_COMPANY = "COMPANY";
    private static final String RULE_KEYWORD = "KEYWORD";

    private static final int REASON_MAX = 300;
    private static final int RAW_VALUE_MAX = 200;

    private final ScoringProperties props;

    /**
     * 单条规则的计算结果。
     *
     * @param ruleName    落库口径的评分项中文名（与 schema 注释一致）
     * @param displayName 拼接 score_reason 时使用的短名（与 ARCHITECTURE §5.2 示例一致）
     */
    public record RuleScore(String ruleCode, String ruleName, String displayName,
                            String rawValue, int weight, int maxScore, int itemScore, String reason) {
    }

    /** 一次评分的完整计算结果 */
    public record Computation(List<RuleScore> rules, List<String> keywordsHit) {
    }

    /** 依据线索字段计算全部规则得分（IRRELEVANT 时各项得分归零，见调用方说明） */
    public Computation compute(Lead lead) {
        IntentCategory intent = IntentCategory.fromCode(lead.getIntentCategory());
        boolean irrelevant = intent == IntentCategory.IRRELEVANT;

        List<String> keywordsHit = matchKeywords(lead);
        List<RuleScore> rules = new ArrayList<>(5);
        rules.add(scoreIntent(intent, irrelevant));
        rules.add(scoreBudget(lead.getBudgetAmount(), irrelevant));
        rules.add(scoreUrgency(lead.getExpectedTime(), irrelevant));
        rules.add(scoreCompany(CompanyScale.fromCode(lead.getCompanyScale()), irrelevant));
        rules.add(scoreKeyword(keywordsHit, irrelevant));
        return new Computation(rules, keywordsHit);
    }

    public ScoringProperties properties() {
        return props;
    }

    private RuleScore scoreIntent(IntentCategory intent, boolean irrelevant) {
        int max = props.getIntent().getMaxScore();
        int raw;
        String rawValue;
        if (intent == null) {
            raw = 0;
            rawValue = "未识别";
        } else {
            rawValue = intent.getLabel();
            raw = switch (intent) {
                case COOPERATION -> props.getIntent().getCooperation();
                case QUOTE -> props.getIntent().getQuote();
                case CONSULT -> props.getIntent().getConsult();
                case COMPLAINT -> props.getIntent().getComplaint();
                case IRRELEVANT -> props.getIntent().getIrrelevant();
            };
        }
        return build(RULE_INTENT, "意图类别", "意图", rawValue, max, raw, irrelevant);
    }

    private RuleScore scoreBudget(BigDecimal amount, boolean irrelevant) {
        ScoringProperties.Budget cfg = props.getBudget();
        int raw;
        String rawValue;
        if (amount == null) {
            raw = cfg.getScoreNone();
            rawValue = "未提及";
        } else if (amount.compareTo(cfg.getAmountHigh()) >= 0) {
            raw = cfg.getScoreHigh();
            rawValue = "50万以上";
        } else if (amount.compareTo(cfg.getAmountMiddle()) >= 0) {
            raw = cfg.getScoreMiddle();
            rawValue = "10万-50万";
        } else if (amount.compareTo(cfg.getAmountLow()) >= 0) {
            raw = cfg.getScoreLow();
            rawValue = "1万-10万";
        } else {
            raw = cfg.getScoreNone();
            rawValue = "1万以下";
        }
        return build(RULE_BUDGET, "预算区间", "预算", rawValue, cfg.getMaxScore(), raw, irrelevant);
    }

    private RuleScore scoreUrgency(LocalDate expectedTime, boolean irrelevant) {
        ScoringProperties.Urgency cfg = props.getUrgency();
        int raw;
        String rawValue;
        if (expectedTime == null) {
            raw = cfg.getScoreNone();
            rawValue = "未提及";
        } else {
            long days = ChronoUnit.DAYS.between(LocalDate.now(), expectedTime);
            if (days <= cfg.getDaysUrgent()) {
                raw = cfg.getScoreUrgent();
                rawValue = cfg.getDaysUrgent() + "天内";
            } else if (days <= cfg.getDaysSoon()) {
                raw = cfg.getScoreSoon();
                rawValue = cfg.getDaysSoon() + "天内";
            } else if (days <= cfg.getDaysNormal()) {
                raw = cfg.getScoreNormal();
                rawValue = cfg.getDaysNormal() + "天内";
            } else {
                raw = cfg.getScoreNone();
                rawValue = cfg.getDaysNormal() + "天以上";
            }
        }
        return build(RULE_URGENCY, "时间紧迫度", "期望时间", rawValue, cfg.getMaxScore(), raw, irrelevant);
    }

    private RuleScore scoreCompany(CompanyScale scale, boolean irrelevant) {
        ScoringProperties.Company cfg = props.getCompany();
        int raw;
        String rawValue;
        if (scale == null) {
            raw = cfg.getNone();
            rawValue = "未知";
        } else {
            rawValue = scale.getLabel();
            raw = switch (scale) {
                case LARGE -> cfg.getLarge();
                case MEDIUM -> cfg.getMedium();
                case SMALL, UNKNOWN -> cfg.getNone();
            };
        }
        return build(RULE_COMPANY, "公司规模", "公司规模", rawValue, cfg.getMaxScore(), raw, irrelevant);
    }

    private RuleScore scoreKeyword(List<String> hits, boolean irrelevant) {
        ScoringProperties.Keyword cfg = props.getKeyword();
        int raw;
        String rawValue;
        if (hits.isEmpty()) {
            raw = 0;
            rawValue = "无";
        } else {
            rawValue = String.join("、", hits);
            raw = hits.size() * cfg.getPerHit();
        }
        return build(RULE_KEYWORD, "关键词命中", "关键词命中", rawValue, cfg.getMaxScore(), raw, irrelevant);
    }

    private RuleScore build(String code, String name, String displayName, String rawValue,
                            int max, int rawScore, boolean irrelevant) {
        int capped = Math.max(0, Math.min(rawScore, max));
        int itemScore = irrelevant ? 0 : capped;
        String reason = displayName + "[" + rawValue + ", +" + itemScore + "]";
        return new RuleScore(code, name, displayName, TextUtils.truncate(rawValue, RAW_VALUE_MAX),
                max, max, itemScore, TextUtils.truncate(reason, REASON_MAX));
    }

    /**
     * 关键词命中：在「主题 + 正文 + 需求要点」中做不区分大小写的子串匹配，
     * 按配置顺序去重统计。
     */
    private List<String> matchKeywords(Lead lead) {
        String source = String.join("\n",
                TextUtils.nullToEmpty(lead.getSubjectTitle()),
                TextUtils.nullToEmpty(lead.getOriginalText()),
                TextUtils.nullToEmpty(lead.getDemandSummary())).toLowerCase(Locale.ROOT);

        Set<String> hits = new LinkedHashSet<>();
        for (String word : props.getKeyword().getWords()) {
            if (TextUtils.isBlank(word)) {
                continue;
            }
            if (source.contains(word.toLowerCase(Locale.ROOT))) {
                hits.add(word.trim());
            }
        }
        return new ArrayList<>(hits);
    }
}
