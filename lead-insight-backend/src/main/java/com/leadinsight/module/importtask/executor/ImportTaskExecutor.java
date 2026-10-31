package com.leadinsight.module.importtask.executor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.leadinsight.common.exception.BizException;
import com.leadinsight.common.result.ResultCode;
import com.leadinsight.module.importtask.entity.ImportTask;
import com.leadinsight.module.importtask.entity.ImportTaskItem;
import com.leadinsight.module.importtask.enums.ImportTaskItemStatus;
import com.leadinsight.module.importtask.enums.ImportTaskStatus;
import com.leadinsight.module.importtask.mapper.ImportTaskItemMapper;
import com.leadinsight.module.importtask.mapper.ImportTaskMapper;
import com.leadinsight.module.importtask.service.impl.ImportTaskRules;
import com.leadinsight.module.lead.dto.LeadCreationOutcome;
import com.leadinsight.module.lead.enums.ExtractStatus;
import com.leadinsight.module.lead.service.LeadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量导入异步执行器（T3-01 亮点2）。
 *
 * <p>@Async 方法只由 ImportTaskServiceImpl 跨 bean 调用，绝不在本类内部自调用——
 * 避免绕过 Spring 代理导致异步失效（与 LeadExtractionProcessor 同款纪律）。
 * 整批任务串行占用 1 个 import-task- 线程，批内逐条：条目置 RUNNING → 调
 * LeadService.createFromBatch（复用单条创建全管线，抽取同步）→ 归桶 → 刷任务计数。</p>
 *
 * <p>归桶口径（openapi 1.3.3 + DEF-04 四桶）：DUPLICATE=指纹命中（catch 40901）；
 * SUCCESS=线索建成且抽取 SUCCESS；DEGRADED=线索建成但抽取 FAILED/DEGRADED（照常产线索，
 * leadId 有值）→ 计入 manual_count（转人工，不再计入 fail_count）；FAILED=创建异常（未产线索）
 * 与兜底异常 → 计入 fail_count。四桶之和 = total_count。</p>
 */
@Slf4j
@Component("importTaskProcessor")
@RequiredArgsConstructor
public class ImportTaskExecutor {

    private final ImportTaskMapper taskMapper;
    private final ImportTaskItemMapper itemMapper;
    private final LeadService leadService;

    /**
     * 异步执行整批任务。segments 与 seq 一一对应（seq = 下标 + 1）。
     * PENDING→RUNNING 用条件更新做幂等防重入：非 PENDING（已被执行过）直接返回。
     */
    @Async("importTaskExecutor")
    public void process(Long taskId, List<String> segments, Long userId, String clientIp) {
        int rows = taskMapper.update(null, Wrappers.<ImportTask>lambdaUpdate()
                .set(ImportTask::getStatus, ImportTaskStatus.RUNNING.name())
                .set(ImportTask::getStartedAt, LocalDateTime.now())
                .eq(ImportTask::getId, taskId)
                .eq(ImportTask::getStatus, ImportTaskStatus.PENDING.name()));
        if (rows == 0) {
            log.warn("批量导入任务重复触发或状态异常，跳过执行: taskId={}", taskId);
            return;
        }

        try {
            for (int i = 0; i < segments.size(); i++) {
                processItem(taskId, i + 1, segments.get(i), userId, clientIp);
            }
            finalizeTask(taskId);
        } catch (Exception e) {
            // 兜底：单条失败不中断整批已在上层处理；此处只防逐条循环之外的意外（DB 断连等）
            log.error("批量导入任务整体异常: taskId={}", taskId, e);
            markFailed(taskId, "任务执行异常: " + e.getMessage());
        }
    }

    private void processItem(Long taskId, int seq, String segment, Long userId, String clientIp) {
        markItemRunning(taskId, seq);
        ImportTaskItem patch = new ImportTaskItem();
        patch.setTaskId(taskId);
        patch.setSeq(seq);

        try {
            LeadCreationOutcome outcome = leadService.createFromBatch(segment, userId, taskId, clientIp);
            // SUCCESS 与 DEGRADED 都产线索（K-1/openapi 1.3.1）：leadId/customerName 两分支统一回填，
            // 否则成功条目明细页无法追溯"这段文本对应哪条线索"，亮点2 逐条可追溯失效。
            // leadId 在本分支必定非空：createInternal 只有在 leadMapper.insert 成功（IdType.AUTO
            // 回填主键）后才返回 outcome；重复内容等未落库路径均先抛 BizException（40901 等），
            // 根本拿不到 outcome，故此处照填安全（null 兜底由 saveItem 直接落 NULL，不抛错）。
            patch.setLeadId(outcome.leadId());
            patch.setCustomerName(outcome.customerName());
            if (outcome.extractStatus() == ExtractStatus.SUCCESS) {
                patch.setItemStatus(ImportTaskItemStatus.SUCCESS.name());
                bumpCounters(taskId, "success_count");
            } else {
                // 线索已建成但抽取 FAILED/DEGRADED：照常产线索，条目记 DEGRADED（leadId 有值）。
                // DEF-04：转人工单列 manual_count，不再计入 fail_count——转人工不是失败
                patch.setItemStatus(ImportTaskItemStatus.DEGRADED.name());
                patch.setReason(outcome.extractMessage());
                bumpCounters(taskId, "manual_count");
            }
        } catch (BizException e) {
            if (e.getResultCode() == ResultCode.DUPLICATE_CONTENT) {
                // 指纹命中：reason 携带已存在线索 id（LeadDedupService 的 40901 文案）
                patch.setItemStatus(ImportTaskItemStatus.DUPLICATE.name());
                patch.setReason(e.getMessage());
                bumpCounters(taskId, "duplicate_count");
            } else {
                log.warn("批量条目创建失败: taskId={} seq={} code={}", taskId, seq, e.getResultCode().getCode(), e);
                patch.setItemStatus(ImportTaskItemStatus.FAILED.name());
                patch.setReason(e.getMessage());
                bumpCounters(taskId, "fail_count");
            }
        } catch (Exception e) {
            log.warn("批量条目创建异常: taskId={} seq={}", taskId, seq, e);
            patch.setItemStatus(ImportTaskItemStatus.FAILED.name());
            patch.setReason("条目处理异常，已转人工");
            bumpCounters(taskId, "fail_count");
        }

        saveItem(patch);
    }

    /** 单条处理完成后刷任务计数行：processed_count + 1 与命中桶 +1（同一条 UPDATE） */
    private void bumpCounters(Long taskId, String bucketColumn) {
        taskMapper.update(null, Wrappers.<ImportTask>lambdaUpdate()
                .setSql("processed_count = processed_count + 1")
                .setSql(bucketColumn + " = " + bucketColumn + " + 1")
                .eq(ImportTask::getId, taskId));
    }

    private void markItemRunning(Long taskId, int seq) {
        itemMapper.update(null, Wrappers.<ImportTaskItem>lambdaUpdate()
                .set(ImportTaskItem::getItemStatus, ImportTaskItemStatus.RUNNING.name())
                .eq(ImportTaskItem::getTaskId, taskId)
                .eq(ImportTaskItem::getSeq, seq));
    }

    private void saveItem(ImportTaskItem patch) {
        itemMapper.update(null, Wrappers.<ImportTaskItem>lambdaUpdate()
                .set(ImportTaskItem::getItemStatus, patch.getItemStatus())
                .set(ImportTaskItem::getLeadId, patch.getLeadId())
                .set(ImportTaskItem::getCustomerName, patch.getCustomerName())
                .set(ImportTaskItem::getReason, patch.getReason())
                .eq(ImportTaskItem::getTaskId, patch.getTaskId())
                .eq(ImportTaskItem::getSeq, patch.getSeq()));
    }

    /** 终态判定（DEF-04 四桶口径）：manual>0 → PARTIAL；fail=0 且 manual=0 → SUCCESS；success=0 且 dup=0 且 manual=0 → FAILED；其余 PARTIAL */
    private void finalizeTask(Long taskId) {
        ImportTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        ImportTaskStatus finalStatus = ImportTaskRules.finalStatus(
                nvl(task.getSuccessCount()), nvl(task.getDuplicateCount()),
                nvl(task.getManualCount()), nvl(task.getFailCount()));
        taskMapper.update(null, Wrappers.<ImportTask>lambdaUpdate()
                .set(ImportTask::getStatus, finalStatus.name())
                .set(ImportTask::getFinishedAt, LocalDateTime.now())
                .eq(ImportTask::getId, taskId)
                .eq(ImportTask::getStatus, ImportTaskStatus.RUNNING.name()));
        log.info("批量导入任务完成: taskId={} status={} total={} processed={} success={} duplicate={} manual={} fail={}",
                taskId, finalStatus, task.getTotalCount(), task.getProcessedCount(),
                task.getSuccessCount(), task.getDuplicateCount(), task.getManualCount(), task.getFailCount());
    }

    private void markFailed(Long taskId, String message) {
        taskMapper.update(null, Wrappers.<ImportTask>lambdaUpdate()
                .set(ImportTask::getStatus, ImportTaskStatus.FAILED.name())
                .set(ImportTask::getErrorMessage, truncate(message, 500))
                .set(ImportTask::getFinishedAt, LocalDateTime.now())
                .eq(ImportTask::getId, taskId)
                .eq(ImportTask::getStatus, ImportTaskStatus.RUNNING.name()));
    }

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
