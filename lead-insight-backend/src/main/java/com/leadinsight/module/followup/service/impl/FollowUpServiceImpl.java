package com.leadinsight.module.followup.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.leadinsight.module.followup.dto.FollowUpCreateRequest;
import com.leadinsight.module.followup.entity.LeadFollowUp;
import com.leadinsight.module.followup.mapper.LeadFollowUpMapper;
import com.leadinsight.module.followup.service.FollowUpService;
import com.leadinsight.module.followup.vo.FollowUpVO;
import com.leadinsight.module.lead.enums.LeadStatus;
import com.leadinsight.module.lead.service.LeadAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 跟进记录实现。跨模块调用只走 LeadAccessService 接口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FollowUpServiceImpl implements FollowUpService {

    private static final String DEFAULT_FOLLOW_TYPE = "NOTE";

    private final LeadFollowUpMapper leadFollowUpMapper;
    private final LeadAccessService leadAccessService;
    private final FollowUpUserEnricher followUpUserEnricher;

    @Override
    public List<FollowUpVO> listByLeadId(Long leadId, Long userId, String role) {
        // 读侧可见性与线索详情同口径（专员仅本人认领 + 未认领池；主管全量只读）
        leadAccessService.getVisible(leadId, userId, role);
        List<LeadFollowUp> rows = leadFollowUpMapper.selectList(
                Wrappers.<LeadFollowUp>lambdaQuery()
                        .eq(LeadFollowUp::getLeadId, leadId)
                        // AC-22：时间倒序；同秒写入按 id 倒序保证顺序稳定可复现
                        .orderByDesc(LeadFollowUp::getCreatedAt)
                        .orderByDesc(LeadFollowUp::getId));
        List<FollowUpVO> result = new ArrayList<>(rows.size());
        for (LeadFollowUp row : rows) {
            result.add(FollowUpVO.from(row));
        }
        // R1 走查 #3：回填记录人姓名（批量 IN，非 N+1）；user_id 为 null 的自动留痕保持 null
        followUpUserEnricher.enrich(result);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FollowUpVO create(Long leadId, FollowUpCreateRequest request, Long userId) {
        // DEF-01：记录跟进必须「已认领且为本人」（PRD §3.3「记录跟进过程：仅已认领线索」）。
        // 未认领一律 40300——自己创建的未认领线索也不例外（仅改 getOperable 覆盖不了该场景）。
        leadAccessService.getClaimed(leadId, userId);
        LeadFollowUp entity = new LeadFollowUp();
        entity.setLeadId(leadId);
        entity.setUserId(userId);
        entity.setFollowType(normalizeFollowType(request.getFollowType()));
        entity.setContent(request.getContent());
        // P-13 / Q04b：显式赋值创建时间，插入后直接用实体装配 VO，省去一次回读。
        // 显式赋值后 insert 会带上 created_at 列，库内值与返回给前端的值必然一致（比依赖 DEFAULT 更准）。
        // 必须截断到秒：lead_follow_up.created_at 是 DATETIME(0)，而 LocalDateTime.now() 带纳秒，
        // 不截断会让响应 JSON 从 "2026-11-01T16:41:55" 变成带小数秒的形态（违反「不改响应 JSON」红线）。
        entity.setCreatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
        leadFollowUpMapper.insert(entity);
        log.debug("新增跟进记录: leadId={} userId={} id={}", leadId, userId, entity.getId());
        FollowUpVO vo = FollowUpVO.from(entity);
        followUpUserEnricher.enrich(vo);
        return vo;
    }

    @Override
    public boolean hasFollowUps(Long leadId) {
        // DEF-02：删除前置校验。含全部来源（手动跟进 + 认领/状态迁移自动留痕）——
        // 只要有任何留痕，删除即被拒绝，审计链不随线索消失。
        // 存在性查询（SELECT 1 ... LIMIT 1）：命中首行即返回，不再数完全部记录
        return leadFollowUpMapper.existsByLeadId(leadId) != null;
    }

    @Override
    public void recordStatusChange(Long leadId, Long userId, LeadStatus from, LeadStatus to, String content) {
        LeadFollowUp entity = new LeadFollowUp();
        entity.setLeadId(leadId);
        entity.setUserId(userId);
        entity.setFollowType(DEFAULT_FOLLOW_TYPE);
        entity.setContent(content);
        entity.setFromStatus(from == null ? null : from.name());
        entity.setToStatus(to == null ? null : to.name());
        leadFollowUpMapper.insert(entity);
    }

    private String normalizeFollowType(String followType) {
        return (followType == null || followType.isBlank()) ? DEFAULT_FOLLOW_TYPE : followType.trim();
    }
}
