package com.leadinsight.module.lead.service;

import com.leadinsight.module.lead.entity.Lead;
import com.leadinsight.module.lead.vo.ScoreDetailVO;

import java.util.List;

/**
 * 优先级评分服务（亮点：可解释优先级排序）。
 */
public interface ScoringService {

    /**
     * 计算并落库评分结果：
     * 更新 lead 的 priority_score / priority_level / score_reason / keywords_hit，
     * 并按 (lead_id, rule_code) 重写 lead_score_detail（先删后插）。
     *
     * @param lead 已持久化（id 非空）的线索实体，方法内会就地回填分数字段
     */
    void score(Lead lead);

    /** 评分明细（按规则写入顺序） */
    List<ScoreDetailVO> listDetails(Long leadId);
}
