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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 读侧可见性 + 写侧可操作性守卫单测（横向越权防护，对齐 PRD §3.3 权限矩阵）。
 * 口径：SUPERVISOR 全量只读；SPECIALIST 仅本人认领 + 未认领池（owner_id IS NULL）。
 * getOperable（DEF-01/02/03 收紧）：未认领须创建者本人、已认领须负责人本人；
 * getClaimed（DEF-01）：写跟进必须已认领且为本人。
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

    private static Lead lead(long id, Long ownerId, Long createdBy) {
        Lead lead = lead(id, ownerId);
        lead.setCreatedBy(createdBy);
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

    // ---------------------------------------------------------- getOperable（DEF-01/02/03）

    @Test
    @DisplayName("getOperable：本人创建的未认领线索 → 放行（PRD §3.3「自己创建」）")
    void operable_allowsOwnUnclaimed() {
        when(leadMapper.selectById(20L)).thenReturn(lead(20L, null, 1L));
        assertNotNull(access.getOperable(20L, 1L));
    }

    @Test
    @DisplayName("getOperable：他人创建且未认领的线索 → 403（DEF-03 越权编辑/删除防线）")
    void operable_deniesOthersUnclaimed() {
        when(leadMapper.selectById(21L)).thenReturn(lead(21L, null, 2L));
        BizException ex = assertThrows(BizException.class, () -> access.getOperable(21L, 1L));
        assertEquals(ResultCode.FORBIDDEN, ex.getResultCode());
        assertEquals(403, ex.getResultCode().getHttpStatus());
        assertTrue(ex.getMessage().contains("他人创建"), "message 须说清原因，实际=" + ex.getMessage());
    }

    @Test
    @DisplayName("getOperable：本人已认领的线索 → 放行（PRD §3.3「已认领」）")
    void operable_allowsOwnClaimed() {
        when(leadMapper.selectById(22L)).thenReturn(lead(22L, 1L, 2L));
        assertNotNull(access.getOperable(22L, 1L));
    }

    @Test
    @DisplayName("getOperable：他人已认领的线索 → 403（回归：原口径保持）")
    void operable_deniesOthersClaimed() {
        when(leadMapper.selectById(23L)).thenReturn(lead(23L, 2L, 2L));
        BizException ex = assertThrows(BizException.class, () -> access.getOperable(23L, 1L));
        assertEquals(ResultCode.FORBIDDEN, ex.getResultCode());
        assertTrue(ex.getMessage().contains("他人认领"), "message 须说清原因，实际=" + ex.getMessage());
    }

    @Test
    @DisplayName("getOperable：未认领且创建者信息缺失（存量脏数据）→ 按最不利口径 403")
    void operable_deniesUnclaimedWithoutCreator() {
        when(leadMapper.selectById(24L)).thenReturn(lead(24L, null, null));
        BizException ex = assertThrows(BizException.class, () -> access.getOperable(24L, 1L));
        assertEquals(ResultCode.FORBIDDEN, ex.getResultCode());
    }

    // ---------------------------------------------------------- getClaimed（DEF-01）

    @Test
    @DisplayName("getClaimed：本人已认领 → 放行")
    void claimed_allowsOwnClaimed() {
        when(leadMapper.selectById(30L)).thenReturn(lead(30L, 1L, 2L));
        assertNotNull(access.getClaimed(30L, 1L));
    }

    @Test
    @DisplayName("getClaimed：未认领（含本人创建）→ 403「请先认领」（DEF-01 核心）")
    void claimed_deniesUnclaimedEvenOwnCreated() {
        when(leadMapper.selectById(31L)).thenReturn(lead(31L, null, 1L));
        BizException ex = assertThrows(BizException.class, () -> access.getClaimed(31L, 1L));
        assertEquals(ResultCode.FORBIDDEN, ex.getResultCode());
        assertEquals(403, ex.getResultCode().getHttpStatus());
        assertTrue(ex.getMessage().contains("认领"), "message 须引导先认领，实际=" + ex.getMessage());
    }

    @Test
    @DisplayName("getClaimed：他人已认领 → 403")
    void claimed_deniesOthersClaimed() {
        when(leadMapper.selectById(32L)).thenReturn(lead(32L, 2L, 2L));
        BizException ex = assertThrows(BizException.class, () -> access.getClaimed(32L, 1L));
        assertEquals(ResultCode.FORBIDDEN, ex.getResultCode());
        assertTrue(ex.getMessage().contains("他人认领"), "message 须说清原因，实际=" + ex.getMessage());
    }

    @Test
    @DisplayName("getClaimed：线索不存在 → 404")
    void claimed_missingLeadNotFound() {
        when(leadMapper.selectById(99L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class, () -> access.getClaimed(99L, 1L));
        assertEquals(ResultCode.NOT_FOUND, ex.getResultCode());
    }
}
