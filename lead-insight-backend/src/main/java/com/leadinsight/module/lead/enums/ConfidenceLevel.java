package com.leadinsight.module.lead.enums;

import lombok.Getter;

import java.math.BigDecimal;

/**
 * 抽取置信度档位（R-2/R-8 裁定，openapi ConfidenceLevel）。
 *
 * <p>系统自算，不采信模型输出。档位与数值一一对应：
 * HIGH=0.9000 / MEDIUM=0.7000 / LOW=0.4000。
 * 语义约定：NULL=未抽取/降级/未评估（仅在 extract_status=SUCCESS 时写入）；
 * 待复核 = IN (MEDIUM, LOW)；幻觉率 = LOW（{@code WHERE confidence_level='LOW'} 单条件即正确）。</p>
 */
@Getter
public enum ConfidenceLevel {

    HIGH(new BigDecimal("0.9000")),
    MEDIUM(new BigDecimal("0.7000")),
    LOW(new BigDecimal("0.4000"));

    /** 落库到 lead.extract_confidence 的数值 */
    private final BigDecimal value;

    ConfidenceLevel(BigDecimal value) {
        this.value = value;
    }

    public static ConfidenceLevel fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim();
        for (ConfidenceLevel level : values()) {
            if (level.name().equalsIgnoreCase(normalized)) {
                return level;
            }
        }
        return null;
    }
}
