package com.leadinsight.module.lead.service.impl;

import com.leadinsight.module.extract.service.ExtractOutcome;
import com.leadinsight.module.extract.service.LeadExtractService;
import com.leadinsight.module.lead.dto.LeadCreateRequest;
import com.leadinsight.module.lead.dto.LeadCreationOutcome;
import com.leadinsight.module.lead.entity.Lead;
import com.leadinsight.module.lead.enums.ExtractStatus;
import com.leadinsight.module.lead.mapper.LeadMapper;
import com.leadinsight.module.lead.service.LeadDedupService;
import com.leadinsight.module.lead.service.ScoringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 线索创建管线（单条 / 批量共用，T3-01 抽离自 LeadServiceImpl 以守住单文件行数门禁）。
 *
 * <p>流程：去重（指纹 + 预查 + 唯一索引兜底）→ 落库 → 注册指纹 → 抽取/评分。
 * 抽取分轨：批量条目强制同步（forceSyncExtract，任务已在后台线程，条目终态须当场判定）；
 * 单条按 J-1 分轨（无 key 同步秒回终态 / 有 key 落 WAITING 后事务提交异步抽取）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LeadCreationProcessor {

    private final LeadMapper leadMapper;
    private final LeadDedupService leadDedupService;
    private final ScoringService scoringService;
    private final LeadExtractService leadExtractService;
    private final LeadExtractionProcessor leadExtractionProcessor;
    private final ManualModeValidator manualModeValidator;

    /** 单条创建（openapi 双模式），返回落库后的 leadId（装配 VO 由调用方负责） */
    public LeadCreationOutcome create(LeadCreateRequest request, Long userId, String clientIp) {
        return createInternal(request, userId, "SINGLE", null, false, clientIp);
    }

    /**
     * 批量导入单条创建（T3-01）：与单条共用管线，落库覆写 import_source=BATCH 并绑定任务 ID；
     * 抽取强制同步。重复内容抛 40901（message 含已存在线索 id）。
     */
    public LeadCreationOutcome createFromBatch(String originalText, Long userId, Long importTaskId, String clientIp) {
        // 批量条目恒为粘贴模式（手工字段为空）
        LeadCreateRequest request = new LeadCreateRequest();
        request.setOriginalText(originalText);
        return createInternal(request, userId, "BATCH", importTaskId, true, clientIp);
    }

    /**
     * 创建管线唯一实现。
     *
     * @param importSource SINGLE / BATCH（schema lead.import_source）
     * @param importTaskId 批量任务 ID（单条为 null）
     * @param forceSyncExtract true=抽取同步执行（批量分轨，无论是否配 key）；
     *                         false=按 J-1 分轨（无 key 同步 / 有 key WAITING+提交后异步）
     * @param clientIp 触发本次创建的来源 IP（成本红线按 IP 封禁用）；非 HTTP 线程传 null
     */
    private LeadCreationOutcome createInternal(LeadCreateRequest request, Long userId,
                                               String importSource, Long importTaskId,
                                               boolean forceSyncExtract, String clientIp) {
        boolean manualMode = request.isManualMode();
        if (manualMode) {
            // AC-08：手工录入模式下客户名称与需求要点必填，逐字段报错
            manualModeValidator.validate(request);
        }

        String fingerprint = leadDedupService.fingerprint(request.getOriginalText());
        leadDedupService.assertNotDuplicate(fingerprint);

        Lead lead = LeadFactory.fromCreateRequest(request, fingerprint, manualMode);
        lead.setImportSource(importSource);
        lead.setImportTaskId(importTaskId);
        // DEF-01/02/03：记录创建人，未认领线索的「仅创建本人可操作」校验依赖此字段（存量行由迁移脚本回填）
        lead.setCreatedBy(userId);

        try {
            leadMapper.insert(lead);
        } catch (DuplicateKeyException ignored) {
            // 并发竞态下标兵：唯一索引 uk_lead_fingerprint 最终拦截。
            // resolveConflict 内部无条件抛 BizException(40901)（见 LeadDedupServiceImpl.resolveConflict），
            // 故此处不会走到下一行——原 `throw e;` 为不可达死代码，已删除。
            log.warn("线索落库命中唯一键冲突，按重复内容处理: fingerprint={}", fingerprint);
            leadDedupService.resolveConflict(fingerprint);
        }

        leadDedupService.register(fingerprint, lead.getId());

        String extractMessage = null;
        if (manualMode) {
            scoringService.score(lead);
        } else if (forceSyncExtract) {
            // 批量分轨（T3-01）：任务已在后台线程池逐条执行，这里同步抽取出终态，
            // 条目状态机（SUCCESS/DEGRADED）据此归桶；限流/熔断/重试/降级编排在 extract 内
            ExtractOutcome outcome = leadExtractService.extract(lead, userId, clientIp);
            extractMessage = outcome.message();
            leadMapper.updateExtractFields(lead);
            scoringService.score(lead);
            log.info("批量条目抽取完成: leadId={} taskId={} status={} message={}",
                    lead.getId(), importTaskId, outcome.status(), outcome.message());
        } else if (leadExtractService.isModelConfigured()) {
            // 有 key 分轨（J-1 / openapi 1.2.3）：落 WAITING 后立即返回 201，抽取在事务提交后
            // 异步执行，前端经 GET /leads/{id} 轮询终态
            leadExtractionProcessor.persistWaiting(lead, userId);
            triggerAfterCommit(lead, userId, clientIp);
        } else {
            // 无 key 分轨（R2 行为零回归）：同步执行抽取编排（限流/熔断/重试/降级，永不抛异常），
            // 返回即时终态。刻意不把模型调用包进 @Transactional：读超时 60s × 3 次的长事务会占满连接池。
            ExtractOutcome outcome = leadExtractService.extract(lead, userId, clientIp);
            extractMessage = outcome.message();
            leadMapper.updateExtractFields(lead);
            scoringService.score(lead);
            log.info("线索粘贴抽取完成: leadId={} status={} message={}",
                    lead.getId(), outcome.status(), outcome.message());
        }

        log.info("线索创建成功: leadId={} userId={} manualMode={} importSource={} score={}",
                lead.getId(), userId, manualMode, importSource, lead.getPriorityScore());
        return new LeadCreationOutcome(lead.getId(), lead.getCustomerName(),
                ExtractStatus.fromCode(lead.getExtractStatus()), extractMessage);
    }

    /**
     * J-1 事务边界硬约束：异步抽取必须在当前事务提交后触发，禁止异步任务读到未提交数据。
     * 当前 create()/reextract() 无 @Transactional（写库自动提交），无事务同步时直接触发
     * 即为"提交后等效"；未来引入事务时，afterCommit 同步器自动保证仍在提交后执行。
     */
    public void triggerAfterCommit(Lead lead, Long userId, String clientIp) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    leadExtractionProcessor.extractAndApplyAsync(lead, userId, clientIp);
                }
            });
        } else {
            leadExtractionProcessor.extractAndApplyAsync(lead, userId, clientIp);
        }
    }
}
