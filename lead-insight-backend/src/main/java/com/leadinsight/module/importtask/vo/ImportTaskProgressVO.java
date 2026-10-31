package com.leadinsight.module.importtask.vo;

import com.leadinsight.module.importtask.entity.ImportTask;
import com.leadinsight.module.importtask.enums.ImportTaskStatus;
import lombok.Data;

/**
 * 任务进度出参（openapi 1.3.1 ImportTaskProgressVO），前端 2s 轮询、终态即停。
 */
@Data
public class ImportTaskProgressVO {

    private Long taskId;
    private ImportTaskStatus status;
    private Integer total;
    private Integer processed;
    private Integer success;
    private Integer duplicate;
    private Integer fail;
    /** 0-100 整数百分比 = processed * 100 / total；total=0 时恒为 0 */
    private Integer percent;

    public static ImportTaskProgressVO from(ImportTask task) {
        ImportTaskProgressVO vo = new ImportTaskProgressVO();
        vo.setTaskId(task.getId());
        vo.setStatus(ImportTaskStatus.fromCode(task.getStatus()));
        int total = task.getTotalCount() == null ? 0 : task.getTotalCount();
        int processed = task.getProcessedCount() == null ? 0 : task.getProcessedCount();
        vo.setTotal(total);
        vo.setProcessed(processed);
        vo.setSuccess(task.getSuccessCount() == null ? 0 : task.getSuccessCount());
        vo.setDuplicate(task.getDuplicateCount() == null ? 0 : task.getDuplicateCount());
        vo.setFail(task.getFailCount() == null ? 0 : task.getFailCount());
        vo.setPercent(total == 0 ? 0 : Math.min(100, processed * 100 / total));
        return vo;
    }
}
