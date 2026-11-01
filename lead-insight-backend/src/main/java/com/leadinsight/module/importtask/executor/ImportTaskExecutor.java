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
 * LeadService.createFromBatch（复用单条创建全管线，抽取同步）→ 归桶 → 计数入内存缓冲。</p>
 *
 * <p><b>计数刷库策略（P-10）</b>：计数不再"每条一次 UPDATE 同一行"，而是内存累加、
 * 每 {@link #FLUSH_INTERVAL_MILLIS} 毫秒刷一次，收尾与失败路径各强制刷一次。
 * 进度接口因此最多滞后约 2 秒（AC-27 只要求"周期性展示进度"，未约束处理中精度）；
 * 终态精确性由收尾强制刷保证，四桶之和 = total_count 不变。</p>
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

    /**
     * 计数刷库间隔（P-10/Q18 选型 C）。取 2 秒而不是"每 10 条"的原因：
     * 批量管线受 1 QPS 模型限流约束，单条约 1 秒，按条数攒批会让进度条滞后 10 秒以上；
     * 按时间刷则滞后恒定 ≤2 秒，与前端 2 秒轮询同频。
     */
    static final long FLUSH_INTERVAL_MILLIS = 2_000L;

    private final ImportTaskMapper taskMapper;
    private final ImportTaskItemMapper itemMapper;
    private final LeadService leadService;

    /** 任务四桶（DEF-04）：SUCCESS / DUPLICATE / MANUAL（转人工）/ FAIL */
    enum Bucket { SUCCESS, DUPLICATE, MANUAL, FAIL }

    /**
     * 任务计数的内存累加器。<b>一次任务一个实例</b>——执行器是单例，做成字段会导致并发任务串账。
     * 只记增量，刷库用 {@code col = col + ?}，因此不需要先读任务行拿基线。
     */
    static final class CounterBuffer {
        private int processed;
        private int success;
        private int duplicate;
        private int manual;
        private int fail;
        private long lastFlushAt = System.currentTimeMillis();

        void record(Bucket bucket) {
            processed++;
            switch (bucket) {
                case SUCCESS -> success++;
                case DUPLICATE -> duplicate++;
                case MANUAL -> manual++;
                case FAIL -> fail++;
            }
        }

        boolean isFlushDue(long now) {
            return processed > 0 && now - lastFlushAt >= FLUSH_INTERVAL_MILLIS;
        }

        boolean isEmpty() {
            return processed == 0;
        }

        void reset(long now) {
            processed = 0;
            success = 0;
            duplicate = 0;
            manual = 0;
            fail = 0;
            lastFlushAt = now;
        }
    }

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

        // 一次任务一个累加器：执行器是单例，缓冲绝不能做成字段（并发任务会串账）
        CounterBuffer counters = new CounterBuffer();
        try {
            for (int i = 0; i < segments.size(); i++) {
                processItem(taskId, i + 1, segments.get(i), userId, clientIp, counters);
            }
            // 收尾强制刷：保证终态计数精确（四桶之和 = total_count），不受 2 秒窗口影响
            flushCounters(taskId, counters);
            finalizeTask(taskId);
        } catch (Exception e) {
            // 兜底：单条失败不中断整批已在上层处理；此处只防逐条循环之外的意外（DB 断连等）
            log.error("批量导入任务整体异常: taskId={}", taskId, e);
            // 失败路径也要先刷，否则已处理条目的计数会随内存一起丢弃
            flushCounters(taskId, counters);
            markFailed(taskId, "任务执行异常: " + e.getMessage());
        }
    }

    private void processItem(Long taskId, int seq, String segment, Long userId, String clientIp,
                             CounterBuffer counters) {
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
                recordAndMaybeFlush(taskId, counters, Bucket.SUCCESS);
            } else {
                // 线索已建成但抽取 FAILED/DEGRADED：照常产线索，条目记 DEGRADED（leadId 有值）。
                // DEF-04：转人工单列 manual_count，不再计入 fail_count——转人工不是失败
                patch.setItemStatus(ImportTaskItemStatus.DEGRADED.name());
                patch.setReason(outcome.extractMessage());
                recordAndMaybeFlush(taskId, counters, Bucket.MANUAL);
            }
        } catch (BizException e) {
            if (e.getResultCode() == ResultCode.DUPLICATE_CONTENT) {
                // 指纹命中：reason 携带已存在线索 id（LeadDedupService 的 40901 文案）
                patch.setItemStatus(ImportTaskItemStatus.DUPLICATE.name());
                patch.setReason(e.getMessage());
                recordAndMaybeFlush(taskId, counters, Bucket.DUPLICATE);
            } else {
                log.warn("批量条目创建失败: taskId={} seq={} code={}", taskId, seq, e.getResultCode().getCode(), e);
                patch.setItemStatus(ImportTaskItemStatus.FAILED.name());
                patch.setReason(e.getMessage());
                recordAndMaybeFlush(taskId, counters, Bucket.FAIL);
            }
        } catch (Exception e) {
            log.warn("批量条目创建异常: taskId={} seq={}", taskId, seq, e);
            patch.setItemStatus(ImportTaskItemStatus.FAILED.name());
            patch.setReason("条目处理异常，已转人工");
            recordAndMaybeFlush(taskId, counters, Bucket.FAIL);
        }

        saveItem(patch);
    }

    /** 归桶后记账；距上次刷库满 {@link #FLUSH_INTERVAL_MILLIS} 才真正落库 */
    private void recordAndMaybeFlush(Long taskId, CounterBuffer counters, Bucket bucket) {
        counters.record(bucket);
        if (counters.isFlushDue(System.currentTimeMillis())) {
            flushCounters(taskId, counters);
        }
    }

    /** 增量一次性写回任务行（空缓冲不产生 SQL）；刷完清零并重置计时窗口 */
    private void flushCounters(Long taskId, CounterBuffer counters) {
        if (counters.isEmpty()) {
            return;
        }
        taskMapper.bumpCounters(taskId, counters.processed, counters.success,
                counters.duplicate, counters.manual, counters.fail);
        log.debug("批量任务计数刷库: taskId={} processed+={} success+={} duplicate+={} manual+={} fail+={}",
                taskId, counters.processed, counters.success, counters.duplicate,
                counters.manual, counters.fail);
        counters.reset(System.currentTimeMillis());
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
