package com.leadinsight.module.lead.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.Getter;

/**
 * 意图类别（内容属性）。IRRELEVANT＝邮件与销售无关（广告/垃圾/自动回复），
 * 与 {@link LeadStatus#INVALID}（不再跟进的结论）语义不同，禁止混用。
 */
@Getter
public enum IntentCategory {

    CONSULT("咨询"),
    QUOTE("报价"),
    COOPERATION("合作"),
    COMPLAINT("投诉"),
    IRRELEVANT("无关");

    private final String label;

    IntentCategory(String label) {
        this.label = label;
    }

    /**
     * JSON 反序列化：空串/null 视为未提供（返回 null），未知取值抛错以触发 40001。
     */
    @JsonCreator
    public static IntentCategory fromJson(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim();
        for (IntentCategory category : values()) {
            if (category.name().equalsIgnoreCase(normalized)) {
                return category;
            }
        }
        throw new IllegalArgumentException("未知的意图类别: " + code);
    }

    public static IntentCategory fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim();
        for (IntentCategory category : values()) {
            if (category.name().equalsIgnoreCase(normalized)) {
                return category;
            }
        }
        return null;
    }
}
