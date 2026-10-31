package com.leadinsight.module.lead.service.impl;

import com.leadinsight.common.web.ClientIpContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.leadinsight.common.exception.BizException;
import com.leadinsight.common.result.PageResult;
import com.leadinsight.common.result.ResultCode;
import com.leadinsight.common.util.TextUtils;
import com.leadinsight.module.followup.service.FollowUpService;
import com.leadinsight.module.importtask.service.ImportTaskAccessService;
import com.leadinsight.module.lead.dto.LeadCreateRequest;
import com.leadinsight.module.lead.dto.LeadQueryRequest;
import com.leadinsight.module.lead.dto.LeadUpdateRequest;
import com.leadinsight.module.lead.dto.StatusChangeRequest;
import com.leadinsight.module.lead.entity.Lead;
import com.leadinsight.module.lead.enums.LeadStatus;
import com.leadinsight.module.lead.mapper.LeadMapper;
import com.leadinsight.module.lead.service.LeadAccessService;
import com.leadinsight.module.lead.service.LeadService;
import com.leadinsight.module.lead.service.ScoringService;
import com.leadinsight.module.lead.vo.LeadDetailVO;
import com.leadinsight.module.lead.vo.LeadVO;
import com.leadinsight.module.lead.vo.ScoreDetailVO;
import com.leadinsight.module.lead.dto.LeadCreationOutcome;
import com.leadinsight.module.extract.service.ExtractOutcome;
import com.leadinsight.module.extract.service.LeadExtractService;
import com.leadinsight.statemachine.LeadStatusMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 线索服务实现（J-1 分轨：无 key 同步秒回终态；有 key 落 WAITING 后立即返回，事务提交后异步抽取）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeadServiceImpl implements LeadService {

    private final LeadMapper leadMapper;
    private final LeadAccessService leadAccessService;
    private final ImportTaskAccessService importTaskAccessService;
    private final ScoringService scoringService;
    private final FollowUpService followUpService;
    private final LeadOwnerEnricher leadOwnerEnricher;
    private final LeadExtractService leadExtractService;
    private final LeadExtractionProcessor leadExtractionProcessor;
    private final LeadCreationProcessor leadCreationProcessor;

    // ------------------------------------------------------------------ 创建（管线委托 LeadCreationProcessor）

    @Override
    public LeadVO create(LeadCreateRequest request, Long userId) {
        LeadCreationOutcome outcome = leadCreationProcessor.create(request, userId, ClientIpContext.current());
        return toVo(leadAccessService.getExisting(outcome.leadId()));
    }

    @Override
    public LeadCreationOutcome createFromBatch(String originalText, Long userId, Long importTaskId, String clientIp) {
        return leadCreationProcessor.createFromBatch(originalText, userId, importTaskId, clientIp);
    }

    // ------------------------------------------------------------------ 查询

    @Override
    public PageResult<LeadVO> page(LeadQueryRequest query, Long userId, String role) {
        int pageNo = LeadQuerySupport.clampPage(query.getPage());
        int limit = LeadQuerySupport.clampLimit(query.getLimit());

        LambdaQueryWrapper<Lead> wrapper = LeadQuerySupport.buildWrapper(query);
        // 读侧可见范围强制过滤（横向越权防护）：专员=本人认领+未认领池；主管=全量只读
        LeadQuerySupport.applyVisibility(wrapper, userId, role);
        LeadQuerySupport.applySort(wrapper, query);

        IPage<Lead> result = leadMapper.selectPage(new Page<>(pageNo, limit), wrapper);
        List<LeadVO> items = result.getRecords().stream().map(LeadVO::from).toList();
        // 整页一次性回填负责人姓名（ownerName），未认领保持 null
        leadOwnerEnricher.enrich(items);
        return PageResult.of(items, result.getTotal(), pageNo, limit);
    }

    @Override
    public PageResult<LeadVO> pageByImportTask(Long importTaskId, Integer page, Integer limit,
                                               Long userId, String role) {
        // 横向越权防护：先校验任务对当前用户可见（专员仅本人任务，主管全量）
        importTaskAccessService.getVisible(importTaskId, userId, role);

        int pageNo = LeadQuerySupport.clampPage(page);
        int pageSize = LeadQuerySupport.clampLimit(limit);
        LambdaQueryWrapper<Lead> wrapper = Wrappers.<Lead>lambdaQuery()
                .eq(Lead::getImportTaskId, importTaskId)
                // 按提交顺序（即条目 seq 对应的创建顺序）展示
                .orderByAsc(Lead::getId);
        IPage<Lead> result = leadMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        List<LeadVO> items = result.getRecords().stream().map(LeadVO::from).toList();
        leadOwnerEnricher.enrich(items);
        return PageResult.of(items, result.getTotal(), pageNo, pageSize);
    }

    @Override
    public LeadDetailVO detail(Long id, Long userId, String role) {
        Lead lead = leadAccessService.getVisible(id, userId, role);
        LeadDetailVO vo = LeadDetailVO.from(lead);
        leadOwnerEnricher.enrich(vo);
        vo.setScores(scoringService.listDetails(id));
        return vo;
    }

    @Override
    public List<ScoreDetailVO> listScores(Long id, Long userId, String role) {
        leadAccessService.getVisible(id, userId, role);
        return scoringService.listDetails(id);
    }

    // ------------------------------------------------------------------ 重新抽取（T2-06 后端）

    @Override
    public LeadVO reextract(Long id, Long userId) {
        Lead lead = leadAccessService.getOperable(id, userId);
        if (TextUtils.isBlank(lead.getOriginalText())) {
            throw new BizException(ResultCode.PARAM_ERROR, "该线索没有原文，无法重新抽取");
        }
        // 与粘贴创建复用同一条抽取编排（限流/熔断/重试/降级）；抽取失败/降级仍返回 200。
        // 两处落库均带归属条件（owner_id IS NULL OR owner_id = :userId）并校验影响行数，
        // 防止「检查后-写入前」被并发认领交错而覆盖他人已认领的线索。
        if (leadExtractService.isModelConfigured()) {
            // 有 key 分轨（J-1）：置 WAITING 落库，事务提交后异步抽取，200 返回 WAITING 供前端轮询
            int rows = leadExtractionProcessor.persistWaiting(lead, userId);
            assertAffected(rows, "重新抽取失败：线索归属已被他人变更，请刷新后重试");
            leadCreationProcessor.triggerAfterCommit(lead, userId, ClientIpContext.current());
        } else {
            // 无 key 分轨（R2 行为零回归）：同步抽取，返回即时终态
            ExtractOutcome outcome = leadExtractService.extract(lead, userId, ClientIpContext.current());
            int rows = updateGuarded(lead, id, userId);
            assertAffected(rows, "重新抽取失败：线索归属已被他人变更，请刷新后重试");
            scoringService.score(lead);
            log.info("线索重新抽取完成: leadId={} status={} userId={} message={}",
                    id, outcome.status(), userId, outcome.message());
        }
        return toVo(leadAccessService.getExisting(id));
    }

    /**
     * J-1 事务边界硬约束见 {@link LeadCreationProcessor#triggerAfterCommit}。
     */

    // ------------------------------------------------------------------ 编辑与删除

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LeadVO update(Long id, LeadUpdateRequest request, Long userId) {
        leadAccessService.getOperable(id, userId);

        Lead patch = new Lead();
        if (request.getCustomerName() != null) {
            patch.setCustomerName(TextUtils.trimToNull(request.getCustomerName()));
        }
        if (request.getCompanyName() != null) {
            patch.setCompanyName(TextUtils.trimToNull(request.getCompanyName()));
        }
        if (request.getContactInfo() != null) {
            patch.setContactInfo(TextUtils.trimToNull(request.getContactInfo()));
        }
        if (request.getIntentCategory() != null) {
            patch.setIntentCategory(request.getIntentCategory().name());
        }
        if (request.getDemandSummary() != null) {
            patch.setDemandSummary(TextUtils.trimToNull(request.getDemandSummary()));
        }
        if (request.getBudgetAmount() != null) {
            patch.setBudgetAmount(request.getBudgetAmount());
        }
        if (request.getExpectedTime() != null) {
            patch.setExpectedTime(request.getExpectedTime());
        }
        if (request.getCompanyScale() != null) {
            patch.setCompanyScale(request.getCompanyScale().name());
        }
        if (request.getRemark() != null) {
            patch.setRemark(TextUtils.trimToNull(request.getRemark()));
        }
        // AC-09：显式刷新修改时间，保证「编辑后 updated_at 变化」可机械判定
        patch.setUpdatedAt(LocalDateTime.now());
        // 并发防护：条件更新带归属条件（owner_id IS NULL OR = 本人），校验影响行数，
        // 防止检查后-写入前被并发认领交错而改到已归属他人的线索
        int rows = updateGuarded(patch, id, userId);
        assertAffected(rows, "编辑失败：线索归属已被他人变更，请刷新后重试");

        Lead updated = leadAccessService.getExisting(id);
        // 人工修正字段会影响评分，编辑后重算
        scoringService.score(updated);
        return toVo(updated);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long userId) {
        leadAccessService.getOperable(id, userId);
        // DEF-02：PRD §3.3 有跟进记录的线索不许删（含认领/状态迁移自动留痕，审计链不随线索从界面消失）
        if (followUpService.hasFollowUps(id)) {
            throw new BizException(ResultCode.FORBIDDEN, "该线索已有跟进记录，不能删除（跟进记录需留痕）");
        }
        // 并发防护：带归属条件的逻辑删除，影响行数为 0 表示归属已变更（他人认领）或已被删除
        int rows = leadMapper.softDeleteByIdAndOwner(id, userId);
        assertAffected(rows, "删除失败：线索归属已被他人变更，请刷新后重试");
        log.info("线索逻辑删除: leadId={} userId={} affectedRows={}", id, userId, rows);
    }

    // ------------------------------------------------------------------ 认领与状态迁移

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LeadVO claim(Long id, Long userId) {
        Lead lead = leadAccessService.getExisting(id);
        LeadStatus from = LeadStatus.fromCode(lead.getStatus());
        LeadStatusMachine.assertTransit(from, LeadStatus.FOLLOWING);

        if (lead.getOwnerId() != null) {
            throw new BizException(ResultCode.ILLEGAL_STATUS_TRANSITION, "该线索已被他人认领，无法重复认领");
        }

        LocalDateTime now = LocalDateTime.now();
        // 并发唯一：条件更新，仅当仍为 NEW 且 owner_id 仍为空时才生效
        int rows = leadMapper.update(null, Wrappers.<Lead>lambdaUpdate()
                .set(Lead::getStatus, LeadStatus.FOLLOWING.name())
                .set(Lead::getOwnerId, userId)
                .set(Lead::getClaimedAt, now)
                .set(Lead::getUpdatedAt, now)
                .eq(Lead::getId, id)
                .eq(Lead::getStatus, LeadStatus.NEW.name())
                .isNull(Lead::getOwnerId));
        if (rows == 0) {
            throw new BizException(ResultCode.ILLEGAL_STATUS_TRANSITION,
                    "认领失败：该线索已被他人认领或状态已变更，请刷新后重试");
        }

        followUpService.recordStatusChange(id, userId, from, LeadStatus.FOLLOWING,
                "认领线索，状态由 " + StatusChangeContent.describe(from) + " 变更为 " + LeadStatus.FOLLOWING.getLabel());
        log.info("线索认领成功: leadId={} ownerId={}", id, userId);
        return toVo(leadAccessService.getExisting(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LeadVO changeStatus(Long id, StatusChangeRequest request, Long userId) {
        Lead lead = leadAccessService.getOperable(id, userId);
        LeadStatus from = LeadStatus.fromCode(lead.getStatus());
        LeadStatus to = request.getToStatus();

        // 非法迁移：此处先抛 42201，绝不写任何数据、不落跟进记录（AC-24）
        LeadStatusMachine.assertTransit(from, to);

        int rows = leadMapper.update(null, Wrappers.<Lead>lambdaUpdate()
                .set(Lead::getStatus, to.name())
                .set(Lead::getUpdatedAt, LocalDateTime.now())
                .eq(Lead::getId, id)
                .eq(Lead::getStatus, from.name()));
        if (rows == 0) {
            throw new BizException(ResultCode.ILLEGAL_STATUS_TRANSITION,
                    "状态迁移失败：线索状态已被其他操作变更，请刷新后重试");
        }

        followUpService.recordStatusChange(id, userId, from, to,
                StatusChangeContent.build(from, to, request.getRemark()));
        log.info("线索状态迁移成功: leadId={} {} -> {} userId={}", id, from, to, userId);
        return toVo(leadAccessService.getExisting(id));
    }

    /** 单条出口统一装配：LeadVO 铺底 + 回填 ownerName（未认领保持 null）。 */
    private LeadVO toVo(Lead lead) {
        LeadVO vo = LeadVO.from(lead);
        leadOwnerEnricher.enrich(vo);
        return vo;
    }

    /**
     * 条件更新（并发防护）：仅在「线索归属仍为未认领或本人」时生效，
     * 与并发认领交错（owner_id 被他人写入）时影响行数为 0。
     * 实体非空字段作为 SET，主键由 wrapper 的 eq(id) 提供（MyBatis-Plus 不会把主键写入 SET）。
     */
    private int updateGuarded(Lead entity, Long id, Long userId) {
        return leadMapper.update(entity, Wrappers.<Lead>lambdaUpdate()
                .eq(Lead::getId, id)
                .and(w -> w.isNull(Lead::getOwnerId).or().eq(Lead::getOwnerId, userId)));
    }

    /** 并发写影响行数为 0 → 归属/状态已被其他操作变更（与认领/状态迁移的并发失败口径一致）。 */
    private void assertAffected(int rows, String message) {
        if (rows == 0) {
            throw new BizException(ResultCode.ILLEGAL_STATUS_TRANSITION, message);
        }
    }
}
