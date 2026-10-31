package com.leadinsight.module.extract.service;

import com.leadinsight.module.extract.parser.ExtractedLead;
import com.leadinsight.module.lead.enums.ConfidenceLevel;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * 抽取置信度自算（T2-03，R-2 裁定：模型不返回 per-field 置信度，系统三段自算）。
 *
 * <p>判定条件（ARCHITECTURE §5.3 亮点1，优先级从上到下）：</p>
 * <ul>
 *   <li>LOW 0.4000 —— 关键字段 ≥2 个为空，或 demand_summary 原文可回溯率 &lt;60%（疑似幻觉）；</li>
 *   <li>HIGH 0.9000 —— 首次解析成功 且 4 个关键字段（customerName/companyName/demandSummary/intentCategory）
 *       全部非空 且 demand_summary 可回溯率 ≥60%；</li>
 *   <li>MEDIUM 0.7000 —— 其余（经 ≥1 次重试才成功，或恰好 1 个关键字段为空）。</li>
 * </ul>
 */
@Component
public class ConfidenceCalculator {

    static final double TRACE_THRESHOLD = 0.60;
    /** 单段回溯探测的最大长度（性能护栏，避免 O(n²·m) 全量探测） */
    private static final int MAX_PROBE = 40;

    /** 置信度评估结果：档位 + 落库数值（一一对应） */
    public record Confidence(ConfidenceLevel level, BigDecimal value) {
    }

    /**
     * @param failedAttempts 本次成功前的失败次数（0=首次成功）
     * @param originalText   客户邮件原文（可回溯率参照物）
     */
    public Confidence assess(ExtractedLead extracted, String originalText, int failedAttempts) {
        // 注意不能用 List.of()：它不允许 null 元素，而关键字段缺失正是要统计的情形
        List<String> keyValues = Arrays.asList(
                extracted.getCustomerName(),
                extracted.getCompanyName(),
                extracted.getDemandSummary(),
                extracted.getIntentCategory());
        long emptyCount = keyValues.stream().filter(v -> v == null || v.isBlank()).count();

        double traceRatio = traceabilityRatio(extracted.getDemandSummary(), originalText);

        if (emptyCount >= 2 || traceRatio < TRACE_THRESHOLD) {
            return of(ConfidenceLevel.LOW);
        }
        boolean allPresent = emptyCount == 0;
        if (failedAttempts == 0 && allPresent && traceRatio >= TRACE_THRESHOLD) {
            return of(ConfidenceLevel.HIGH);
        }
        return of(ConfidenceLevel.MEDIUM);
    }

    /**
     * demand_summary 在原文中的可回溯率：贪婪匹配 needle 的最长可回溯片段，
     * 命中字符数 / 总字符数（双方先去除全部空白，规避排版差异）。
     */
    double traceabilityRatio(String needleRaw, String haystackRaw) {
        String needle = stripAllWhitespace(needleRaw);
        String haystack = stripAllWhitespace(haystackRaw);
        if (needle.isEmpty()) {
            return 1.0;
        }
        if (haystack.isEmpty()) {
            return 0.0;
        }
        int matched = 0;
        int i = 0;
        while (i < needle.length()) {
            int maxLen = Math.min(needle.length() - i, MAX_PROBE);
            int best = 0;
            for (int len = maxLen; len >= 1; len--) {
                if (haystack.contains(needle.substring(i, i + len))) {
                    best = len;
                    break;
                }
            }
            i += best == 0 ? 1 : best;
            matched += best;
        }
        return (double) matched / needle.length();
    }

    private String stripAllWhitespace(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private Confidence of(ConfidenceLevel level) {
        return new Confidence(level, level.getValue());
    }
}
