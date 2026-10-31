package com.leadinsight.module.lead.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Getter;

/**
 * 公司规模。UNKNOWN 与 SMALL 在评分中同为 0 分。
 */
@Getter
public enum CompanyScale {

    LARGE("大型"),
    MEDIUM("中型"),
    SMALL("小型"),
    UNKNOWN("未知");

    private final String label;

    CompanyScale(String label) {
        this.label = label;
    }

    @JsonCreator
    public static CompanyScale fromJson(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim();
        for (CompanyScale scale : values()) {
            if (scale.name().equalsIgnoreCase(normalized)) {
                return scale;
            }
        }
        throw new IllegalArgumentException("未知的公司规模: " + code);
    }

    public static CompanyScale fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim();
        for (CompanyScale scale : values()) {
            if (scale.name().equalsIgnoreCase(normalized)) {
                return scale;
            }
        }
        return null;
    }
}
