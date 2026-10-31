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

    /** 新增手动跟进记录（需通过「已认领且为本人」校验，AC-21 + PRD §3.3，DEF-01） */
    FollowUpVO create(Long leadId, FollowUpCreateRequest request, Long userId);

    /**
     * 该线索是否已有任何跟进记录（含认领/状态迁移自动留痕）。
     * 删除线索的前置校验（DEF-02：PRD §3.3「删除线索：仅自己创建且未被跟进」）。
     */
    boolean hasFollowUps(Long leadId);

    /** 状态迁移成功后自动留痕（AC-23） */
    void recordStatusChange(Long leadId, Long userId, LeadStatus from, LeadStatus to, String content);
}
