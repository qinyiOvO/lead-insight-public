package com.leadinsight.module.followup.service.impl;

import com.leadinsight.common.exception.BizException;
import com.leadinsight.common.result.ResultCode;
import com.leadinsight.module.followup.dto.FollowUpCreateRequest;
import com.leadinsight.module.followup.entity.LeadFollowUp;
import com.leadinsight.module.followup.mapper.LeadFollowUpMapper;
import com.leadinsight.module.followup.vo.FollowUpVO;
import com.leadinsight.module.lead.entity.Lead;
import com.leadinsight.module.lead.service.LeadAccessService;
import com.leadinsight.module.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 写跟进的认领校验单测（DEF-01：PRD §3.3「记录跟进过程：仅已认领线索」）。
 *
 * <p>核心场景：对未认领（owner_id IS NULL）线索写跟进必须 40300——**包括自己创建的未认领线索**，
 * 仅收紧 getOperable 覆盖不了该场景（创建者会被放行），故 create() 改走 LeadAccessService.getClaimed。</p>
 */
class FollowUpServiceImplClaimTest {

    private final LeadFollowUpMapper followUpMapper = mock(LeadFollowUpMapper.class);
    private final LeadAccessService leadAccessService = mock(LeadAccessService.class);
    private final UserService userService = mock(UserService.class);

    private final FollowUpServiceImpl service = new FollowUpServiceImpl(followUpMapper,
            leadAccessService, new FollowUpUserEnricher(userService));

    private static FollowUpCreateRequest request() {
        FollowUpCreateRequest request = new FollowUpCreateRequest();
        request.setFollowType("NOTE");
        request.setContent("TST-跟进内容");
        return request;
    }

    @Test
    @DisplayName("DEF-01：未认领线索写跟进 → 40300 且不落库（含本人创建的未认领线索）")
    void create_unclaimedLeadForbidden() {
        when(leadAccessService.getClaimed(1L, 1L)).thenThrow(new BizException(
                ResultCode.FORBIDDEN, "该线索尚未认领，请先认领后再记录跟进"));

        BizException ex = assertThrows(BizException.class,
                () -> service.create(1L, request(), 1L));

        assertEquals(ResultCode.FORBIDDEN, ex.getResultCode());
        assertEquals(403, ex.getResultCode().getHttpStatus());
        assertTrue(ex.getMessage().contains("认领"), "message 须引导先认领，实际=" + ex.getMessage());
        verify(followUpMapper, never()).insert(any(LeadFollowUp.class));
    }

    @Test
    @DisplayName("他人已认领的线索写跟进 → 40300 且不落库")
    void create_othersClaimedLeadForbidden() {
        when(leadAccessService.getClaimed(2L, 1L)).thenThrow(new BizException(
                ResultCode.FORBIDDEN, "该线索已由他人认领，无法记录跟进"));

        BizException ex = assertThrows(BizException.class,
                () -> service.create(2L, request(), 1L));

        assertEquals(ResultCode.FORBIDDEN, ex.getResultCode());
        verify(followUpMapper, never()).insert(any(LeadFollowUp.class));
    }

    @Test
    @DisplayName("本人已认领的线索写跟进 → 放行并落库（正常路径不回归）")
    void create_ownClaimedLeadSucceeds() {
        Lead claimed = new Lead();
        claimed.setId(3L);
        claimed.setOwnerId(1L);
        when(leadAccessService.getClaimed(3L, 1L)).thenReturn(claimed);
        when(followUpMapper.insert(any(LeadFollowUp.class))).thenAnswer(inv -> {
            inv.<LeadFollowUp>getArgument(0).setId(50L);
            return 1;
        });

        FollowUpVO vo = service.create(3L, request(), 1L);

        assertNotNull(vo);
        verify(leadAccessService).getClaimed(3L, 1L);
        verify(followUpMapper).insert(any(LeadFollowUp.class));
        // P-13 / Q04b：写后不再整行回读，VO 由插入前的实体直接装配
        verify(followUpMapper, never()).selectById(any());
        assertEquals(50L, vo.getId(), "id 须取插入回填的自增值");
        assertNotNull(vo.getCreatedAt(), "createdAt 须在 insert 前显式赋值，否则序列化为 null");
        assertEquals(0, vo.getCreatedAt().getNano(),
                "createdAt 须截断到秒，与 DATETIME(0) 精度一致，否则响应 JSON 形态变化");
    }

    // ---------------------------------------------------------------- hasFollowUps（DEF-02 删除前置）

    @Test
    @DisplayName("hasFollowUps：有跟进记录（含自动留痕）→ true")
    void hasFollowUps_trueWhenRowsExist() {
        when(followUpMapper.existsByLeadId(9L)).thenReturn(1);
        assertEquals(true, service.hasFollowUps(9L));
    }

    @Test
    @DisplayName("hasFollowUps：无任何跟进 → false")
    void hasFollowUps_falseWhenNoRows() {
        when(followUpMapper.existsByLeadId(9L)).thenReturn(null);
        assertEquals(false, service.hasFollowUps(9L));
    }
}
