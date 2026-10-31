package com.leadinsight.module.lead.vo;

import com.leadinsight.module.lead.entity.Lead;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 线索详情出参（openapi LeadDetailVO = LeadVO + 明细字段 + 评分明细）。
 * expectedTime / ownerName 继承自 LeadVO，此处不重复声明（避免子类字段遮蔽父类）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class LeadDetailVO extends LeadVO {

    private String originalText;
    private String contactInfo;
    private BigDecimal budgetAmount;
    private String budgetText;
    private String expectedTimeText;
    private String companyScale;
    private String keywordsHit;
    /** 评分明细（逐项可解释） */
    private List<ScoreDetailVO> scores = new ArrayList<>();

    public static LeadDetailVO from(Lead lead) {
        LeadDetailVO vo = new LeadDetailVO();
        LeadVO base = LeadVO.from(lead);
        vo.setId(base.getId());
        vo.setSubjectTitle(base.getSubjectTitle());
        vo.setCustomerName(base.getCustomerName());
        vo.setCompanyName(base.getCompanyName());
        vo.setIntentCategory(base.getIntentCategory());
        vo.setDemandSummary(base.getDemandSummary());
        vo.setExpectedTime(base.getExpectedTime());
        vo.setPriorityScore(base.getPriorityScore());
        vo.setPriorityLevel(base.getPriorityLevel());
        vo.setScoreReason(base.getScoreReason());
        vo.setStatus(base.getStatus());
        vo.setExtractStatus(base.getExtractStatus());
        vo.setExtractConfidence(base.getExtractConfidence());
        vo.setConfidenceLevel(base.getConfidenceLevel());
        vo.setOwnerId(base.getOwnerId());
        vo.setOwnerName(base.getOwnerName());
        vo.setImportSource(base.getImportSource());
        vo.setCreatedAt(base.getCreatedAt());

        vo.setOriginalText(lead.getOriginalText());
        vo.setContactInfo(lead.getContactInfo());
        vo.setBudgetAmount(lead.getBudgetAmount());
        vo.setBudgetText(lead.getBudgetText());
        vo.setExpectedTimeText(lead.getExpectedTimeText());
        vo.setCompanyScale(lead.getCompanyScale());
        vo.setKeywordsHit(lead.getKeywordsHit());
        return vo;
    }
}
