package com.leadinsight.module.lead.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.leadinsight.common.util.TextUtils;
import com.leadinsight.config.ScoringProperties;
import com.leadinsight.module.lead.entity.Lead;
import com.leadinsight.module.lead.entity.LeadScoreDetail;
import com.leadinsight.module.lead.enums.IntentCategory;
import com.leadinsight.module.lead.enums.PriorityLevel;
import com.leadinsight.module.lead.mapper.LeadMapper;
import com.leadinsight.module.lead.mapper.LeadScoreDetailMapper;
import com.leadinsight.module.lead.service.ScoringService;
import com.leadinsight.module.lead.vo.ScoreDetailVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 优先级评分实现（ARCHITECTURE-v1.1 §5.2）。
 *
 * <p>已登记的解释口径：意图为 IRRELEVANT 时总分强制 0、等级 LOW，
 * 且各规则 item_score 一并归零，保证「明细之和 == 总分」，
 * 避免评审时出现分项与合计对不上的歧义；score_reason 末尾追加
 *「判定为无关邮件，不参与优先级排序。」</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScoringServiceImpl implements ScoringService {

    private static final String IRRELEVANT_NOTE = "判定为无关邮件，不参与优先级排序。";

    private static final int SCORE_REASON_MAX = 1000;
    private static final int KEYWORDS_HIT_MAX = 500;

    private final ScoringRuleCalculator calculator;
    private final LeadMapper leadMapper;
    private final LeadScoreDetailMapper scoreDetailMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void score(Lead lead) {
        if (lead == null || lead.getId() == null) {
            throw new IllegalArgumentException("评分前线索必须已持久化（id 非空）");
        }

        ScoringRuleCalculator.Computation computation = calculator.compute(lead);
        boolean irrelevant = IntentCategory.IRRELEVANT == IntentCategory.fromCode(lead.getIntentCategory());
        int total = irrelevant ? 0
                : computation.rules().stream().mapToInt(ScoringRuleCalculator.RuleScore::itemScore).sum();
        PriorityLevel level = resolveLevel(total);
        String reason = buildReason(computation.rules(), total, level, irrelevant);
        String keywordsHit = TextUtils.truncate(String.join(",", computation.keywordsHit()), KEYWORDS_HIT_MAX);

        // 就地回填，便于调用方直接组装 VO
        lead.setPriorityScore(total);
        lead.setPriorityLevel(level.name());
        lead.setScoreReason(reason);
        lead.setKeywordsHit(keywordsHit.isEmpty() ? null : keywordsHit);

        updateLeadScoreFields(lead.getId(), total, level, reason, lead.getKeywordsHit());
        rewriteDetails(lead.getId(), computation.rules());

        log.debug("线索评分完成: leadId={} total={} level={} keywords={}",
                lead.getId(), total, level.name(), keywordsHit);
    }

    @Override
    public List<ScoreDetailVO> listDetails(Long leadId) {
        if (leadId == null) {
            return List.of();
        }
        List<LeadScoreDetail> details = scoreDetailMapper.selectList(
                Wrappers.<LeadScoreDetail>lambdaQuery()
                        .eq(LeadScoreDetail::getLeadId, leadId)
                        .orderByAsc(LeadScoreDetail::getId));
        List<ScoreDetailVO> result = new ArrayList<>(details.size());
        for (LeadScoreDetail detail : details) {
            ScoreDetailVO vo = new ScoreDetailVO();
            vo.setRuleCode(detail.getRuleCode());
            vo.setRuleName(detail.getRuleName());
            vo.setRawValue(detail.getRawValue());
            vo.setWeight(detail.getWeight());
            vo.setItemScore(detail.getItemScore());
            vo.setMaxScore(detail.getMaxScore());
            vo.setReason(detail.getReason());
            result.add(vo);
        }
        return result;
    }

    private PriorityLevel resolveLevel(int total) {
        ScoringProperties.Level level = calculator.properties().getLevel();
        if (total >= level.getHigh()) {
            return PriorityLevel.HIGH;
        }
        if (total >= level.getMedium()) {
            return PriorityLevel.MEDIUM;
        }
        return PriorityLevel.LOW;
    }

    /** 拼接可解释依据，格式对齐 ARCHITECTURE §5.2 示例 */
    private String buildReason(List<ScoringRuleCalculator.RuleScore> rules, int total,
                               PriorityLevel level, boolean irrelevant) {
        StringBuilder sb = new StringBuilder();
        for (ScoringRuleCalculator.RuleScore rule : rules) {
            if (!sb.isEmpty()) {
                sb.append('；');
            }
            sb.append(rule.displayName()).append('[').append(rule.rawValue())
                    .append(", +").append(rule.itemScore()).append(']');
        }
        sb.append("；合计 ").append(total).append(" 分，判定为 ").append(level.getLabel()).append(" 优先级。");
        if (irrelevant) {
            sb.append(IRRELEVANT_NOTE);
        }
        return TextUtils.truncate(sb.toString(), SCORE_REASON_MAX);
    }

    private void updateLeadScoreFields(Long leadId, int total, PriorityLevel level,
                                       String reason, String keywordsHit) {
        Lead patch = new Lead();
        patch.setId(leadId);
        patch.setPriorityScore(total);
        patch.setPriorityLevel(level.name());
        patch.setScoreReason(reason);
        patch.setKeywordsHit(keywordsHit);
        leadMapper.updateById(patch);
    }

    private void rewriteDetails(Long leadId, List<ScoringRuleCalculator.RuleScore> rules) {
        scoreDetailMapper.deleteByLeadId(leadId);
        for (ScoringRuleCalculator.RuleScore rule : rules) {
            LeadScoreDetail detail = new LeadScoreDetail();
            detail.setLeadId(leadId);
            detail.setRuleCode(rule.ruleCode());
            detail.setRuleName(rule.ruleName());
            detail.setRawValue(rule.rawValue());
            detail.setWeight(rule.weight());
            detail.setItemScore(rule.itemScore());
            detail.setMaxScore(rule.maxScore());
            detail.setReason(rule.reason());
            scoreDetailMapper.insert(detail);
        }
    }
}
