package com.leadinsight.module.lead.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.leadinsight.module.extract.service.ExtractOutcome;
import com.leadinsight.module.extract.service.LeadExtractService;
import com.leadinsight.module.lead.entity.Lead;
import com.leadinsight.module.lead.enums.ExtractStatus;
import com.leadinsight.module.lead.mapper.LeadMapper;
import com.leadinsight.module.lead.service.ScoringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 异步抽取执行器（J-1 裁定）。
 *
 * <p>分轨说明：无 key 时 create()/reextract() 保持同步秒回 DEGRADED（R2 行为零回归），
 * 本组件只在配 key 的分轨被触发。@Async 方法（extractAndApplyAsync）与持久化入口
 * （persistWaiting）虽在同一 bean，但异步方法<b>只由 LeadServiceImpl 跨 bean 调用</b>，
 * 绝不在本类内部自调用——避免绕过 Spring 代理导致异步失效。</p>
 *
 * <p>事务边界硬约束（J-1 ③）：异步抽取只在当前事务提交后触发——LeadServiceImpl 侧用
 * afterCommit 同步器（或无事务时的等效直接触发），本组件不感知事务，禁止读到未提交数据。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LeadExtractionProcessor {

    private final LeadExtractService leadExtractService;
    private final LeadMapper leadMapper;
    private final ScoringService scoringService;

    /**
     * 抽取受理：置 WAITING 并落库（新建线索由 LeadFactory 已落 WAITING，此处覆盖
     * 重抽取等旧终态场景），返回影响行数。落库带归属条件
     * （{@code owner_id IS NULL OR owner_id = :userId}），与并发认领交错时影响行数为 0，
     * 调用方据此判定并抛并发失败。调用方随后在事务提交后触发 {@link #extractAndApplyAsync}。
     */
    public int persistWaiting(Lead lead, Long userId) {
        lead.setExtractStatus(ExtractStatus.WAITING.name());
        return leadMapper.update(lead, Wrappers.<Lead>lambdaUpdate()
                .eq(Lead::getId, lead.getId())
                .and(w -> w.isNull(Lead::getOwnerId).or().eq(Lead::getOwnerId, userId)));
    }

    /**
     * 异步执行抽取编排并落库评分（线程池 llmExtractExecutor：有界队列 + CallerRuns）。
     * 只读传入 lead 的 originalText，抽取终态与字段由编排回填后由本组件持久化。
     */
    @Async("llmExtractExecutor")
    public void extractAndApplyAsync(Lead lead, Long operatorId, String clientIp) {
        try {
            ExtractOutcome outcome = leadExtractService.extract(lead, operatorId, clientIp);
            leadMapper.updateById(lead);
            scoringService.score(lead);
            log.info("异步线索抽取完成: leadId={} status={} message={}",
                    lead.getId(), outcome.status(), outcome.message());
        } catch (Exception e) {
            // 兜底：extract 编排承诺永不抛异常，此处仅防御未来回归——任何异常折算 DEGRADED 落库，
            // 线索绝不停留在 WAITING（前端轮询必须能等到终态）
            log.error("异步线索抽取异常，折算降级终态: leadId={}", lead.getId(), e);
            lead.setExtractStatus(ExtractStatus.DEGRADED.name());
            lead.setExtractConfidence(null);
            lead.setConfidenceLevel(null);
            leadMapper.updateById(lead);
        }
    }
}
