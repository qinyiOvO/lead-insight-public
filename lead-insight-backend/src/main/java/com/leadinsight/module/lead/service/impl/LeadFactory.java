package com.leadinsight.module.lead.service.impl;

import com.leadinsight.common.util.TextUtils;
import com.leadinsight.module.lead.dto.LeadCreateRequest;
import com.leadinsight.module.lead.entity.Lead;
import com.leadinsight.module.lead.enums.ExtractStatus;
import com.leadinsight.module.lead.enums.LeadStatus;
import com.leadinsight.module.lead.enums.PriorityLevel;

/**
 * Lead 实体装配（create 专用）。抽离自 LeadServiceImpl，保持单一职责与文件行数约束。
 */
final class LeadFactory {

    private LeadFactory() {
    }

    /**
     * 按双模式装配新线索：手工字段直接生效（不调用模型），粘贴模式仅落正文骨架，
     * 抽取字段由抽取编排回填。
     */
    static Lead fromCreateRequest(LeadCreateRequest request, String fingerprint, boolean manualMode) {
        Lead lead = new Lead();
        lead.setContentFingerprint(fingerprint);
        lead.setSubjectTitle(TextUtils.trimToNull(request.getSubjectTitle()));
        lead.setOriginalText(request.getOriginalText());
        lead.setRemark(TextUtils.trimToNull(request.getRemark()));
        lead.setImportSource("SINGLE");
        lead.setStatus(LeadStatus.NEW.name());
        lead.setExtractStatus(ExtractStatus.WAITING.name());
        lead.setConfidenceLevel(null);
        lead.setPriorityScore(0);
        lead.setPriorityLevel(PriorityLevel.LOW.name());

        if (manualMode) {
            lead.setCustomerName(TextUtils.trimToNull(request.getCustomerName()));
            lead.setCompanyName(TextUtils.trimToNull(request.getCompanyName()));
            lead.setContactInfo(TextUtils.trimToNull(request.getContactInfo()));
            lead.setIntentCategory(request.getIntentCategory() == null
                    ? null : request.getIntentCategory().name());
            lead.setDemandSummary(TextUtils.trimToNull(request.getDemandSummary()));
            lead.setBudgetAmount(request.getBudgetAmount());
            lead.setExpectedTime(request.getExpectedTime());
            lead.setCompanyScale(request.getCompanyScale() == null
                    ? null : request.getCompanyScale().name());
        }
        return lead;
    }
}
