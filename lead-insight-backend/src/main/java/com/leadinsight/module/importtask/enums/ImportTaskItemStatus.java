package com.leadinsight.module.importtask.enums;

import lombok.Getter;

/**
 * 批量导入逐条明细状态（openapi ImportTaskItemVO.itemStatus，六值）。
 *
 * <p>DUPLICATE=指纹命中跳过（不产线索）；DEGRADED=线索已创建但抽取降级/失败转人工
 * （照常产线索，leadId 有值，与单条粘贴行为一致，openapi 1.3.1 裁定）。</p>
 */
@Getter
public enum ImportTaskItemStatus {

    PENDING("待处理"),
    RUNNING("处理中"),
    SUCCESS("成功"),
    DUPLICATE("重复跳过"),
    FAILED("失败转人工"),
    DEGRADED("降级产线索");

    private final String label;

    ImportTaskItemStatus(String label) {
        this.label = label;
    }

    public static ImportTaskItemStatus fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim();
        for (ImportTaskItemStatus status : values()) {
            if (status.name().equalsIgnoreCase(normalized)) {
                return status;
            }
        }
        return null;
    }
}
