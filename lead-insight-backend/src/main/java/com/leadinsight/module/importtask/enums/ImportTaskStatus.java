package com.leadinsight.module.importtask.enums;

import lombok.Getter;

/**
 * 批量导入任务状态（openapi ImportTaskVO.status，五值）。
 *
 * <p>终态判定按 K-1 裁定：SUCCESS=转人工/降级=0；PARTIAL=混合；
 * FAILED=全部条目转人工/降级或创建异常（无一自动成功）。全部去重命中仍算 SUCCESS。</p>
 */
@Getter
public enum ImportTaskStatus {

    PENDING("待执行"),
    RUNNING("执行中"),
    SUCCESS("全部完成"),
    PARTIAL("部分失败"),
    FAILED("整体失败");

    private final String label;

    ImportTaskStatus(String label) {
        this.label = label;
    }

    public static ImportTaskStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim();
        for (ImportTaskStatus status : values()) {
            if (status.name().equalsIgnoreCase(normalized)) {
                return status;
            }
        }
        return null;
    }
}
