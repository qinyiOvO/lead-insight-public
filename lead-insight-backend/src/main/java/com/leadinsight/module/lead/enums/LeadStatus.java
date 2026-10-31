package com.leadinsight.module.lead.enums;

import lombok.Getter;

/**
 * 线索状态（跟进结论）。与 {@link IntentCategory}（内容属性）是两个本质不同的概念，
 * 代码层严禁共用同一枚举、严禁裸字面量（ARCHITECTURE-v1.1 §5.2 R-6）。
 */
@Getter
public enum LeadStatus {

    NEW("新线索"),
    FOLLOWING("跟进中"),
    WON("已成交"),
    INVALID("无效");

    /** 中文名称，用于错误提示与评分依据拼接 */
    private final String label;

    LeadStatus(String label) {
        this.label = label;
    }

    public static LeadStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim();
        for (LeadStatus status : values()) {
            if (status.name().equalsIgnoreCase(normalized)) {
                return status;
            }
        }
        return null;
    }
}
