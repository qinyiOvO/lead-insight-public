package com.leadinsight.module.followup.service.impl;

import com.leadinsight.common.exception.BizException;
import com.leadinsight.common.result.ResultCode;
import com.leadinsight.module.followup.mapper.LeadFollowUpMapper;
import com.leadinsight.module.lead.entity.Lead;
import com.leadinsight.module.lead.service.LeadAccessService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跟进列表读侧可见性单测（横向越权防护）：跟进列表与线索详情同口径，
 * 专员读他人已认领线索的跟进 → 403；未认领 → 放行。与同文件 create() 的归属口径统一。
 */
class FollowUpServiceImplVisibilityTest {

    private final LeadFollowUpMapper followUpMapper = mock(LeadFollowUpMapper.class);
    private final LeadAccessService leadAccessService = mock(LeadAccessService.class);
    private final FollowUpUserEnricher followUpUserEnricher = mock(FollowUpUserEnricher.class);

    private final FollowUpServiceImpl service =
            new FollowUpServiceImpl(followUpMapper, leadAccessService, followUpUserEnricher);

    @Test
    @DisplayName("专员读他人已认领线索的跟进列表：抛 FORBIDDEN(403) 且不查库")
    void listForbiddenForOthersLead() {
        when(leadAccessService.getVisible(3L, 1L, "SPECIALIST"))
                .thenThrow(new BizException(ResultCode.FORBIDDEN, "该线索已由他人认领，无权查看"));

        BizException ex = assertThrows(BizException.class,
                () -> service.listByLeadId(3L, 1L, "SPECIALIST"));

        assertEquals(ResultCode.FORBIDDEN, ex.getResultCode());
        assertEquals(403, ex.getResultCode().getHttpStatus());
        verify(followUpMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("专员读未认领线索的跟进列表：放行（未认领池可见）")
    void listAllowedForUnclaimedLead() {
        when(leadAccessService.getVisible(4L, 1L, "SPECIALIST")).thenReturn(new Lead());
        when(followUpMapper.selectList(any())).thenReturn(List.of());

        assertTrue(service.listByLeadId(4L, 1L, "SPECIALIST").isEmpty());
        verify(leadAccessService).getVisible(4L, 1L, "SPECIALIST");
        verify(followUpMapper).selectList(any());
    }

    @Test
    @DisplayName("主管读任意线索的跟进列表：放行（全量只读）")
    void listAllowedForSupervisor() {
        when(leadAccessService.getVisible(5L, 1L, "SUPERVISOR")).thenReturn(new Lead());
        when(followUpMapper.selectList(any())).thenReturn(List.of());

        assertTrue(service.listByLeadId(5L, 1L, "SUPERVISOR").isEmpty());
        verify(leadAccessService).getVisible(5L, 1L, "SUPERVISOR");
    }
}
