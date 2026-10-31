package com.leadinsight.module.lead.enums;

import lombok.Getter;

/**
 * 优先级等级。阈值口径见 ARCHITECTURE-v1.1 §5.2：&gt;=70 HIGH / 40-69 MEDIUM / &lt;40 LOW。
 */
@Getter
public enum PriorityLevel {

    HIGH("高"),
    MEDIUM("中"),
    LOW("低");

    private final String label;

    PriorityLevel(String label) {
        this.label = label;
    }

    public static PriorityLevel fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim();
        for (PriorityLevel level : values()) {
            if (level.name().equalsIgnoreCase(normalized)) {
                return level;
            }
        }
        return null;
    }
}
