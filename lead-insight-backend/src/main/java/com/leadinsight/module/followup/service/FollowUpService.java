package com.leadinsight.module.followup.service;

import com.leadinsight.module.followup.dto.FollowUpCreateRequest;
import com.leadinsight.module.followup.vo.FollowUpVO;
import com.leadinsight.module.lead.enums.LeadStatus;

import java.util.List;

/**
 * 跟进记录服务。
 */
public interface FollowUpService {

    /** 按线索查询跟进记录，created_at 倒序（AC-22）；读侧横向越权防护，不可见抛 40300 */
    List<FollowUpVO> listByLeadId(Long leadId, Long userId, String role);

    /** 新增手动跟进记录（需通过归属校验，AC-21） */
    FollowUpVO create(Long leadId, FollowUpCreateRequest request, Long userId);

    /** 状态迁移成功后自动留痕（AC-23） */
    void recordStatusChange(Long leadId, Long userId, LeadStatus from, LeadStatus to, String content);
}
