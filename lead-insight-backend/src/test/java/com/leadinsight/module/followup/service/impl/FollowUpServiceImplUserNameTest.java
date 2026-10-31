package com.leadinsight.module.followup.service.impl;

import com.leadinsight.module.followup.entity.LeadFollowUp;
import com.leadinsight.module.followup.mapper.LeadFollowUpMapper;
import com.leadinsight.module.followup.vo.FollowUpVO;
import com.leadinsight.module.lead.entity.Lead;
import com.leadinsight.module.lead.service.LeadAccessService;
import com.leadinsight.module.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跟进记录人姓名回填单测（openapi FollowUpVO.userName，R1 走查 #3）。
 *
 * <p>「谁跟的」是跟进留痕可读性的前提：user_id 是数字 id，界面不能只显示"用户 #3"。
 * 回填语义等价 LEFT JOIN sys_user：命中 → real_name；用户已不存在 / real_name 为空 →
 * 保持 null（绝不写空串）；user_id 为 null（系统自动留痕未关联）→ null 且不触发用户查询。</p>
 */
class FollowUpServiceImplUserNameTest {

    private final LeadFollowUpMapper followUpMapper = mock(LeadFollowUpMapper.class);
    private final LeadAccessService leadAccessService = mock(LeadAccessService.class);
    private final UserService userService = mock(UserService.class);

    private final FollowUpServiceImpl service = new FollowUpServiceImpl(followUpMapper,
            leadAccessService, new FollowUpUserEnricher(userService));

    @BeforeEach
    void allowVisibility() {
        when(leadAccessService.getVisible(any(), any(), any())).thenReturn(new Lead());
        // DEF-01：create 走「已认领且为本人」校验（getClaimed），不再是 getOperable
        when(leadAccessService.getClaimed(any(), any())).thenReturn(new Lead());
    }

    private static LeadFollowUp row(Long id, Long userId, String content) {
        LeadFollowUp entity = new LeadFollowUp();
        entity.setId(id);
        entity.setLeadId(1L);
        entity.setUserId(userId);
        entity.setFollowType("NOTE");
        entity.setContent(content);
        return entity;
    }

    @Test
    @DisplayName("列表回填：user_id 命中 → userName=real_name（批量 IN，非 N+1）")
    void listBackfillsUserNameFromRealName() {
        when(followUpMapper.selectList(any())).thenReturn(List.of(
                row(11L, 3L, "电话沟通需求"),
                row(12L, 4L, "发报价单")));
        when(userService.realNameMapByIds(anyCollection()))
                .thenReturn(Map.of(3L, "王销售", 4L, "李销售"));

        List<FollowUpVO> result = service.listByLeadId(1L, 2L, "SUPERVISOR");

        assertEquals(2, result.size());
        assertEquals("王销售", result.get(0).getUserName());
        assertEquals("李销售", result.get(1).getUserName());
        verify(userService).realNameMapByIds(anyCollection());
    }

    @Test
    @DisplayName("用户已不存在 / real_name 为空 → userName 保持 null，绝不写空串")
    void missingUserKeepsNullNotEmptyString() {
        when(followUpMapper.selectList(any())).thenReturn(List.of(
                row(13L, 99L, "用户已被删除的留痕"),
                row(14L, 5L, "real_name 为空串的留痕")));
        // 99L 查不到；5L 查到但 real_name 是空串
        when(userService.realNameMapByIds(anyCollection())).thenReturn(Map.of(5L, ""));

        List<FollowUpVO> result = service.listByLeadId(1L, 2L, "SUPERVISOR");

        assertNull(result.get(0).getUserName(), "查不到的用户必须保持 null");
        assertNull(result.get(1).getUserName(), "real_name 为空必须折算为 null，不得写空串");
    }

    @Test
    @DisplayName("user_id 为 null（系统自动留痕未关联）→ userName 为 null，且不触发用户查询")
    void nullUserIdSkipsUserLookup() {
        when(followUpMapper.selectList(any())).thenReturn(List.of(row(15L, null, "系统状态留痕")));

        List<FollowUpVO> result = service.listByLeadId(1L, 2L, "SUPERVISOR");

        assertNull(result.get(0).getUserName());
        verify(userService, never()).realNameMapByIds(anyCollection());
    }

    @Test
    @DisplayName("create 返回单条也回填 userName（新增跟进立即可见「谁跟的」）")
    void createAlsoBackfillsUserName() {
        when(followUpMapper.insert(any(LeadFollowUp.class))).thenAnswer(inv -> {
            inv.<LeadFollowUp>getArgument(0).setId(21L);
            return 1;
        });
        when(followUpMapper.selectById(21L)).thenReturn(row(21L, 3L, "上门拜访"));
        when(userService.realNameMapByIds(anyCollection())).thenReturn(Map.of(3L, "王销售"));

        FollowUpVO vo = service.create(1L,
                new com.leadinsight.module.followup.dto.FollowUpCreateRequest(), 3L);

        assertEquals("王销售", vo.getUserName());
    }
}
