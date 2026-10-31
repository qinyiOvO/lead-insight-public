package com.leadinsight.module.extract.service;

import com.leadinsight.module.extract.parser.ExtractedLead;
import com.leadinsight.module.lead.enums.ConfidenceLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 置信度自算单测（T2-03，AC-37/38：HIGH 0.9 / MEDIUM 0.7 / LOW 0.4，系统自算不采信模型）。
 */
class ConfidenceCalculatorTest {

    private final ConfidenceCalculator calculator = new ConfidenceCalculator();

    private static final String ORIGINAL =
            "客户：广州振华五金制品有限公司 张三 13800138000，计划采购304不锈钢管500米，"
                    + "预算60万元，希望7天内拿到正式报价并签订合同。";

    private ExtractedLead fullLead() {
        ExtractedLead lead = new ExtractedLead();
        lead.setCustomerName("张三");
        lead.setCompanyName("广州振华五金制品有限公司");
        lead.setIntentCategory("QUOTE");
        lead.setDemandSummary("采购304不锈钢管500米，希望7天内拿到正式报价");
        return lead;
    }

    @Test
    @DisplayName("首次成功 + 四关键字段齐全 + 回溯率达标 → HIGH 0.9000")
    void highPath() {
        ConfidenceCalculator.Confidence c = calculator.assess(fullLead(), ORIGINAL, 0);
        assertEquals(ConfidenceLevel.HIGH, c.level());
        assertEquals("0.9000", c.value().toPlainString());
    }

    @Test
    @DisplayName("≥2 个关键字段为空 → 强制 LOW 0.4000")
    void twoEmptyForcesLow() {
        ExtractedLead lead = fullLead();
        lead.setCustomerName(null);
        lead.setCompanyName(null);
        ConfidenceCalculator.Confidence c = calculator.assess(lead, ORIGINAL, 0);
        assertEquals(ConfidenceLevel.LOW, c.level());
        assertEquals("0.4000", c.value().toPlainString());
    }

    @Test
    @DisplayName("回溯率 <60%（摘要与原文脱节，疑似幻觉）→ 强制 LOW")
    void lowTraceabilityForcesLow() {
        ExtractedLead lead = fullLead();
        lead.setDemandSummary("采购一批智能手机与平板电脑，需要分期付款方案");
        ConfidenceCalculator.Confidence c = calculator.assess(lead, ORIGINAL, 0);
        assertEquals(ConfidenceLevel.LOW, c.level());
        assertTrue(calculator.traceabilityRatio(lead.getDemandSummary(), ORIGINAL) < 0.60);
    }

    @Test
    @DisplayName("经 1 次重试才成功（字段齐全且回溯达标）→ MEDIUM 0.7000")
    void retriedIsMedium() {
        ConfidenceCalculator.Confidence c = calculator.assess(fullLead(), ORIGINAL, 1);
        assertEquals(ConfidenceLevel.MEDIUM, c.level());
        assertEquals("0.7000", c.value().toPlainString());
    }

    @Test
    @DisplayName("恰好 1 个关键字段为空 → MEDIUM")
    void oneEmptyIsMedium() {
        ExtractedLead lead = fullLead();
        lead.setCompanyName(null);
        ConfidenceCalculator.Confidence c = calculator.assess(lead, ORIGINAL, 0);
        assertEquals(ConfidenceLevel.MEDIUM, c.level());
    }

    @Test
    @DisplayName("回溯率计算：完全回溯=1.0，无关文本=0，空白差异不影响")
    void traceabilityRatioBasics() {
        String summary = "采购304不锈钢管500米";
        assertEquals(1.0, calculator.traceabilityRatio(summary, "计划采购 304 不锈钢管 500 米"), 1e-9);
        // 选用与摘要无任何同字的参照文本（注意 "不" 等常用字会造成单字命中）
        assertEquals(0.0, calculator.traceabilityRatio(summary, "xyzabcuv"), 1e-9);
        assertTrue(calculator.traceabilityRatio(summary, "采购") < 0.6);
    }
}
