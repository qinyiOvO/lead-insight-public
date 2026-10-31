package com.leadinsight.module.lead.service.impl;

import com.leadinsight.common.exception.BizException;
import com.leadinsight.common.result.ResultCode;
import com.leadinsight.module.lead.entity.Lead;
import com.leadinsight.module.lead.mapper.LeadMapper;
import com.leadinsight.module.lead.service.LeadAccessService;
import com.leadinsight.module.user.enums.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 线索访问守卫实现。只做存在性与归属校验，不含业务规则计算。
 */
@Service
@RequiredArgsConstructor
public class LeadAccessServiceImpl implements LeadAccessService {

    private final LeadMapper leadMapper;

    @Override
    public Lead getExisting(Long leadId) {
        if (leadId == null) {
            throw new BizException(ResultCode.NOT_FOUND, "线索不存在");
        }
        Lead lead = leadMapper.selectById(leadId);
        if (lead == null) {
            throw new BizException(ResultCode.NOT_FOUND, "线索不存在或已删除");
        }
        return lead;
    }

    @Override
    public Lead getOperable(Long leadId, Long userId) {
        Lead lead = getExisting(leadId);
        Long ownerId = lead.getOwnerId();
        if (ownerId != null) {
            // 已认领：仅负责人本人可操作（他人已认领一律拒绝）
            if (!Objects.equals(ownerId, userId)) {
                throw new BizException(ResultCode.FORBIDDEN, "该线索已由他人认领，无法操作");
            }
            return lead;
        }
        // 未认领：仅创建者本人可操作（DEF-01/02/03：他人创建且未认领的线索不可编辑/删除）
        Long createdBy = lead.getCreatedBy();
        if (createdBy == null || !Objects.equals(createdBy, userId)) {
            throw new BizException(ResultCode.FORBIDDEN,
                    "该线索由他人创建且尚未认领，无法操作（可认领后跟进，或联系创建人处理）");
        }
        return lead;
    }

    @Override
    public Lead getClaimed(Long leadId, Long userId) {
        Lead lead = getExisting(leadId);
        Long ownerId = lead.getOwnerId();
        if (ownerId == null) {
            // DEF-01：未认领线索不可写跟进（PRD §3.3「记录跟进过程：仅已认领线索」）
            throw new BizException(ResultCode.FORBIDDEN, "该线索尚未认领，请先认领后再记录跟进");
        }
        if (!Objects.equals(ownerId, userId)) {
            throw new BizException(ResultCode.FORBIDDEN, "该线索已由他人认领，无法记录跟进");
        }
        return lead;
    }

    @Override
    public Lead getVisible(Long leadId, Long userId, String role) {
        Lead lead = getExisting(leadId);
        // 主管全量只读；专员只见本人认领 + 未认领池（owner_id IS NULL）
        if (UserRole.fromCode(role) == UserRole.SUPERVISOR) {
            return lead;
        }
        Long ownerId = lead.getOwnerId();
        if (ownerId != null && !Objects.equals(ownerId, userId)) {
            throw new BizException(ResultCode.FORBIDDEN, "该线索已由他人认领，无权查看");
        }
        return lead;
    }
}
