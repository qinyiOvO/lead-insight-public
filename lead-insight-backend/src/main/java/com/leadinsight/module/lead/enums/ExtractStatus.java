package com.leadinsight.module.lead.enums;

import lombok.Getter;

/**
 * 抽取状态。R1 为纯本机无模型环境，手工录入线索落 WAITING（待抽取）。
 */
@Getter
public enum ExtractStatus {

    WAITING("待抽取"),
    PROCESSING("抽取中"),
    SUCCESS("解析成功"),
    FAILED("解析失败转人工"),
    DEGRADED("模型不可用降级");

    private final String label;

    ExtractStatus(String label) {
        this.label = label;
    }

    public static ExtractStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim();
        for (ExtractStatus status : values()) {
            if (status.name().equalsIgnoreCase(normalized)) {
                return status;
            }
        }
        return null;
    }
}
