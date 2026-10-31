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
        if (ownerId != null && !Objects.equals(ownerId, userId)) {
            throw new BizException(ResultCode.FORBIDDEN, "该线索已由他人认领，无法操作");
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
