package com.leadinsight.module.lead.dto;

import com.leadinsight.module.lead.enums.ExtractStatus;

/**
 * 单条线索创建结果（批量导入 T3-01 专用出参）。
 *
 * <p>批量执行器据 extractStatus 归桶：SUCCESS → 条目 SUCCESS；
 * FAILED/DEGRADED → 条目 DEGRADED（照常产线索，openapi 1.3.1 裁定，leadId 有值）。
 * 重复内容不走本出参——去重命中时 LeadService.createFromBatch 直接抛 40901。</p>
 */
public record LeadCreationOutcome(
        Long leadId,
        String customerName,
        ExtractStatus extractStatus,
        String extractMessage
) {
}
