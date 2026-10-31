package com.leadinsight.module.followup.vo;

import com.leadinsight.module.followup.entity.LeadFollowUp;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 跟进记录出参（openapi FollowUpVO，v1.3.3 追加 userName）。
 *
 * <p>{@code userName}（记录人姓名）不来自 {@link LeadFollowUp} 实体本身，取自 sys_user.real_name，
 * 由 {@link com.leadinsight.module.followup.service.impl.FollowUpUserEnricher} 按 user_id 批量回填
 * （与线索域 {@code LeadOwnerEnricher} 同款做法，避免 N+1），故 {@link #from} 不铺该字段。</p>
 */
@Data
public class FollowUpVO {

    private Long id;
    private Long leadId;
    private Long userId;
    /** 记录人姓名（sys_user.real_name，批量回填）；操作人缺失 / 用户已不存在为 null，绝不写空串 */
    private String userName;
    private String followType;
    private String content;
    private String fromStatus;
    private String toStatus;
    private LocalDateTime createdAt;

    public static FollowUpVO from(LeadFollowUp entity) {
        FollowUpVO vo = new FollowUpVO();
        vo.setId(entity.getId());
        vo.setLeadId(entity.getLeadId());
        vo.setUserId(entity.getUserId());
        vo.setFollowType(entity.getFollowType());
        vo.setContent(entity.getContent());
        vo.setFromStatus(entity.getFromStatus());
        vo.setToStatus(entity.getToStatus());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
