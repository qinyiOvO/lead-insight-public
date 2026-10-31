package com.leadinsight.module.lead.service.impl;

import com.leadinsight.common.exception.BizException;
import com.leadinsight.common.result.ResultCode;
import com.leadinsight.module.lead.entity.Lead;
import com.leadinsight.module.lead.mapper.LeadMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 读侧可见性守卫单测（横向越权防护，对齐 PRD §3.3 权限矩阵）。
 * 口径：SUPERVISOR 全量只读；SPECIALIST 仅本人认领 + 未认领池（owner_id IS NULL）。
 */
class LeadAccessServiceImplTest {

    private final LeadMapper leadMapper = mock(LeadMapper.class);
    private final LeadAccessServiceImpl access = new LeadAccessServiceImpl(leadMapper);

    private static Lead lead(long id, Long ownerId) {
        Lead lead = new Lead();
        lead.setId(id);
        lead.setOwnerId(ownerId);
        return lead;
    }

    @Test
    @DisplayName("专员读未认领线索详情：放行（未认领池可见，别误封）")
    void specialistCanViewUnclaimed() {
        when(leadMapper.selectById(10L)).thenReturn(lead(10L, null));
        assertNotNull(access.getVisible(10L, 1L, "SPECIALIST"));
    }

    @Test
    @DisplayName("专员读本人已认领线索：放行")
    void specialistCanViewOwn() {
        when(leadMapper.selectById(11L)).thenReturn(lead(11L, 1L));
        assertNotNull(access.getVisible(11L, 1L, "SPECIALIST"));
    }

    @Test
    @DisplayName("专员读他人已认领线索：抛 FORBIDDEN（HTTP 403）")
    void specialistCannotViewOthers() {
        when(leadMapper.selectById(12L)).thenReturn(lead(12L, 2L));
        BizException ex = assertThrows(BizException.class,
                () -> access.getVisible(12L, 1L, "SPECIALIST"));
        assertEquals(ResultCode.FORBIDDEN, ex.getResultCode());
        assertEquals(403, ex.getResultCode().getHttpStatus());
    }

    @Test
    @DisplayName("主管读他人已认领线索：放行（全量只读）")
    void supervisorCanViewAll() {
        when(leadMapper.selectById(13L)).thenReturn(lead(13L, 2L));
        assertNotNull(access.getVisible(13L, 1L, "SUPERVISOR"));
    }

    @Test
    @DisplayName("role 不可识别：按最不利口径拒绝（视为专员）")
    void unknownRoleDenied() {
        when(leadMapper.selectById(14L)).thenReturn(lead(14L, 2L));
        assertThrows(BizException.class, () -> access.getVisible(14L, 1L, null));
    }

    @Test
    @DisplayName("线索不存在：抛 NOT_FOUND（HTTP 404）")
    void missingLeadNotFound() {
        when(leadMapper.selectById(99L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class,
                () -> access.getVisible(99L, 1L, "SPECIALIST"));
        assertEquals(ResultCode.NOT_FOUND, ex.getResultCode());
    }

    @Test
    @DisplayName("getOperable 保持原口径：未认领或本人可操作，他人已认领 403")
    void operableStillGuarded() {
        when(leadMapper.selectById(20L)).thenReturn(lead(20L, null));
        assertNotNull(access.getOperable(20L, 1L));

        when(leadMapper.selectById(21L)).thenReturn(lead(21L, 2L));
        BizException ex = assertThrows(BizException.class, () -> access.getOperable(21L, 1L));
        assertEquals(ResultCode.FORBIDDEN, ex.getResultCode());
    }
}
