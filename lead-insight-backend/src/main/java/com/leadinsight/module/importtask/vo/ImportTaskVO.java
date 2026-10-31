package com.leadinsight.module.importtask.vo;

import com.leadinsight.module.importtask.entity.ImportTask;
import com.leadinsight.module.importtask.enums.ImportTaskStatus;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 批量导入任务出参（openapi 1.3.1 ImportTaskVO）。
 *
 * <p>字段名与契约严格一致；契约不含 errorMessage/createdBy，故不映射（不擅自扩字段）。</p>
 */
@Data
public class ImportTaskVO {

    private Long id;
    private String taskNo;
    private String fileName;
    private String importSource;
    private Integer totalCount;
    private Integer processedCount;
    private Integer successCount;
    private Integer duplicateCount;
    private Integer failCount;
    private ImportTaskStatus status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;

    public static ImportTaskVO from(ImportTask task) {
        ImportTaskVO vo = new ImportTaskVO();
        vo.setId(task.getId());
        vo.setTaskNo(task.getTaskNo());
        vo.setFileName(task.getFileName());
        vo.setImportSource(task.getImportSource());
        vo.setTotalCount(task.getTotalCount());
        vo.setProcessedCount(task.getProcessedCount());
        vo.setSuccessCount(task.getSuccessCount());
        vo.setDuplicateCount(task.getDuplicateCount());
        vo.setFailCount(task.getFailCount());
        vo.setStatus(ImportTaskStatus.fromCode(task.getStatus()));
        vo.setStartedAt(task.getStartedAt());
        vo.setFinishedAt(task.getFinishedAt());
        vo.setCreatedAt(task.getCreatedAt());
        return vo;
    }
}
