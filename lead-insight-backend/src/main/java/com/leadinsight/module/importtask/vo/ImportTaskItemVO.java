package com.leadinsight.module.importtask.vo;

import com.leadinsight.module.importtask.entity.ImportTaskItem;
import com.leadinsight.module.importtask.enums.ImportTaskItemStatus;
import lombok.Data;

/**
 * 批量导入逐条明细出参（openapi 1.3.1 ImportTaskItemVO）。
 *
 * <p>seq 从 1 起；itemStatus 六值。leadId：SUCCESS/DEGRADED 有值（两态均产线索），
 * DUPLICATE/FAILED 为 null。reason 承载 DUPLICATE/FAILED/DEGRADED 的说明文案。</p>
 */
@Data
public class ImportTaskItemVO {

    private Integer seq;
    private ImportTaskItemStatus itemStatus;
    private Long leadId;
    private String customerName;
    private String reason;

    public static ImportTaskItemVO from(ImportTaskItem item) {
        ImportTaskItemVO vo = new ImportTaskItemVO();
        vo.setSeq(item.getSeq());
        vo.setItemStatus(ImportTaskItemStatus.fromCode(item.getItemStatus()));
        vo.setLeadId(item.getLeadId());
        vo.setCustomerName(item.getCustomerName());
        vo.setReason(item.getReason());
        return vo;
    }
}
