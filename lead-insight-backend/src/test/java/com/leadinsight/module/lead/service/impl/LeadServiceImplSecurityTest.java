package com.leadinsight.module.lead.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.leadinsight.common.exception.BizException;
import com.leadinsight.common.result.ResultCode;
import com.leadinsight.module.extract.service.ExtractOutcome;
import com.leadinsight.module.extract.service.LeadExtractService;
import com.leadinsight.module.followup.service.FollowUpService;
import com.leadinsight.module.importtask.service.ImportTaskAccessService;
import com.leadinsight.module.lead.dto.LeadQueryRequest;
import com.leadinsight.module.lead.dto.LeadUpdateRequest;
import com.leadinsight.module.lead.entity.Lead;
import com.leadinsight.module.lead.enums.ExtractStatus;
import com.leadinsight.module.lead.mapper.LeadMapper;
import com.leadinsight.module.lead.service.LeadAccessService;
import com.leadinsight.module.lead.service.ScoringService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 线索服务读侧可见性 + 写侧竞态防护单测（横向越权 / TOCTOU）。
 *
 * <p>Mockito 手打桩（不起 Spring）。读侧委托 {@link LeadAccessService#getVisible}，故此处同时校验
 * ①列表 wrapper 的归属限定（专员）/ 无限定（主管）②详情与评分明细走可见性并阻断评分泄漏
 * ③写操作（编辑/删除/重抽）影响行数为 0 时抛并发失败异常。</p>
 */
class LeadServiceImplSecurityTest {

    private final LeadMapper leadMapper = mock(LeadMapper.class);
    private final LeadAccessService leadAccessService = mock(LeadAccessService.class);
    private final ImportTaskAccessService importTaskAccessService = mock(ImportTaskAccessService.class);
    private final ScoringService scoringService = mock(ScoringService.class);
    private final FollowUpService followUpService = mock(FollowUpService.class);
    private final LeadOwnerEnricher leadOwnerEnricher = mock(LeadOwnerEnricher.class);
    private final LeadExtractService leadExtractService = mock(LeadExtractService.class);
    private final LeadExtractionProcessor leadExtractionProcessor = mock(LeadExtractionProcessor.class);
    private final LeadCreationProcessor leadCreationProcessor = mock(LeadCreationProcessor.class);

    private final LeadServiceImpl service = new LeadServiceImpl(leadMapper, leadAccessService,
            importTaskAccessService, scoringService, followUpService, leadOwnerEnricher,
            leadExtractService, leadExtractionProcessor, leadCreationProcessor);

    static Lead lead(long id, Long ownerId) {
        Lead lead = new Lead();
        lead.setId(id);
        lead.setOwnerId(ownerId);
        return lead;
    }

    @BeforeAll
    static void initTableInfo() {
        // 纯单测无 Spring/MP 启动流程，lambda → 列名 解析需要 TableInfo 缓存（否则 getSqlSegment 抛
        // "can not find lambda cache for this entity"），手工初始化
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Lead.class);
    }

    @SuppressWarnings("unchecked")
    private AbstractWrapper<Lead, ?, ?> capturePageWrapper() {
        ArgumentCaptor<Wrapper<Lead>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(leadMapper, atLeastOnce()).selectPage(any(Page.class), captor.capture());
        return (AbstractWrapper<Lead, ?, ?>) captor.getValue();
    }

    // ---------------------------------------------------------- 列表可见范围（C-1）

    @Test
    @DisplayName("专员列表：强制追加 (owner_id = 本人 OR owner_id IS NULL)")
    void page_specialistScopedToOwnAndUnclaimed() {
        when(leadMapper.selectPage(any(Page.class), any())).thenAnswer(inv -> inv.getArgument(0));

        service.page(new LeadQueryRequest(), 7L, "SPECIALIST");

        AbstractWrapper<Lead, ?, ?> wrapper = capturePageWrapper();
        String sql = wrapper.getSqlSegment().toLowerCase();
        assertTrue(sql.contains("owner_id"), "专员列表必须带归属限定");
        assertTrue(sql.contains("is null"), "专员列表必须包含未认领池条件（owner_id IS NULL）");
        assertTrue(wrapper.getParamNameValuePairs().values().contains(7L), "必须绑定当前用户 id");
    }

    @Test
    @DisplayName("主管列表：不加归属限定（全量只读）")
    void page_supervisorUnscoped() {
        when(leadMapper.selectPage(any(Page.class), any())).thenAnswer(inv -> inv.getArgument(0));

        service.page(new LeadQueryRequest(), 7L, "SUPERVISOR");

        String sql = capturePageWrapper().getSqlSegment().toLowerCase();
        assertTrue(!sql.contains("owner_id"), "主管列表不得追加归属限定，实际 SQL=" + sql);
    }

    // ---------------------------------------------------------- 详情 / 评分明细（C-2 / C-3）

    @Test
    @DisplayName("专员读他人已认领线索详情：抛 FORBIDDEN(403) 且不泄漏评分明细")
    void detail_forbiddenForOthersLead() {
        when(leadAccessService.getVisible(7L, 1L, "SPECIALIST"))
                .thenThrow(new BizException(ResultCode.FORBIDDEN, "该线索已由他人认领，无权查看"));

        BizException ex = assertThrows(BizException.class, () -> service.detail(7L, 1L, "SPECIALIST"));
        assertEquals(ResultCode.FORBIDDEN, ex.getResultCode());
        assertEquals(403, ex.getResultCode().getHttpStatus());
        verify(scoringService, never()).listDetails(any());
    }

    @Test
    @DisplayName("专员读他人已认领线索评分明细：抛 FORBIDDEN(403)")
    void listScores_forbiddenForOthersLead() {
        when(leadAccessService.getVisible(8L, 1L, "SPECIALIST"))
                .thenThrow(new BizException(ResultCode.FORBIDDEN, "该线索已由他人认领，无权查看"));

        BizException ex = assertThrows(BizException.class, () -> service.listScores(8L, 1L, "SPECIALIST"));
        assertEquals(ResultCode.FORBIDDEN, ex.getResultCode());
        verify(scoringService, never()).listDetails(8L);
    }

    @Test
    @DisplayName("主管读他人线索详情：放行（全量只读）")
    void detail_supervisorAllowed() {
        Lead lead = lead(9L, 2L);
        when(leadAccessService.getVisible(9L, 1L, "SUPERVISOR")).thenReturn(lead);
        when(scoringService.listDetails(9L)).thenReturn(java.util.List.of());

        assertNotNull(service.detail(9L, 1L, "SUPERVISOR"));
        verify(scoringService).listDetails(9L);
    }

    // ---------------------------------------------------------- 写侧竞态（TOCTOU）

    @Test
    @DisplayName("竞态：编辑影响行数为 0（归属已被他人变更）→ 抛异常，不重算评分")
    void update_zeroRowsThrows() {
        when(leadAccessService.getOperable(5L, 1L)).thenReturn(lead(5L, null));
        when(leadMapper.update(any(), any())).thenReturn(0);

        LeadUpdateRequest request = new LeadUpdateRequest();
        request.setCustomerName("新名字");

        BizException ex = assertThrows(BizException.class, () -> service.update(5L, request, 1L));
        assertEquals(ResultCode.ILLEGAL_STATUS_TRANSITION, ex.getResultCode());
        assertTrue(ex.getMessage().contains("刷新后重试"));
        verify(scoringService, never()).score(any());
    }

    @Test
    @DisplayName("编辑正常（影响行数=1）：更新带归属条件并重算评分")
    void update_success() {
        Lead reloaded = lead(5L, null);
        when(leadAccessService.getOperable(5L, 1L)).thenReturn(lead(5L, null));
        when(leadMapper.update(any(), any())).thenReturn(1);
        when(leadAccessService.getExisting(5L)).thenReturn(reloaded);

        LeadUpdateRequest request = new LeadUpdateRequest();
        request.setCustomerName("新名字");
        assertNotNull(service.update(5L, request, 1L));

        ArgumentCaptor<Lead> patchCaptor = ArgumentCaptor.forClass(Lead.class);
        verify(leadMapper).update(patchCaptor.capture(), any());
        assertEquals("新名字", patchCaptor.getValue().getCustomerName());
        assertNotNull(patchCaptor.getValue().getUpdatedAt(), "编辑必须显式刷新 updated_at（AC-09）");
        verify(scoringService).score(reloaded);
    }

    @Test
    @DisplayName("竞态：删除影响行数为 0（归属已被他人变更）→ 抛异常")
    void delete_zeroRowsThrows() {
        when(leadAccessService.getOperable(6L, 1L)).thenReturn(lead(6L, null));
        when(leadMapper.softDeleteByIdAndOwner(6L, 1L)).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () -> service.delete(6L, 1L));
        assertEquals(ResultCode.ILLEGAL_STATUS_TRANSITION, ex.getResultCode());
    }

    @Test
    @DisplayName("DEF-02：已有跟进记录的线索删除被拒（40300 且不执行软删）")
    void delete_withFollowUpsRejected() {
        when(leadAccessService.getOperable(6L, 1L)).thenReturn(lead(6L, 1L));
        when(followUpService.hasFollowUps(6L)).thenReturn(true);

        BizException ex = assertThrows(BizException.class, () -> service.delete(6L, 1L));
        assertEquals(ResultCode.FORBIDDEN, ex.getResultCode());
        assertEquals(403, ex.getResultCode().getHttpStatus());
        assertTrue(ex.getMessage().contains("跟进"), "message 须说清原因，实际=" + ex.getMessage());
        // 被拒即不触库：软删一次都不许发生
        verify(leadMapper, never()).softDeleteByIdAndOwner(any(), any());
    }

    @Test
    @DisplayName("DEF-02：无任何跟进记录的线索允许删除（含未认领的自建线索）")
    void delete_withoutFollowUpsAllowed() {
        when(leadAccessService.getOperable(6L, 1L)).thenReturn(lead(6L, null));
        when(followUpService.hasFollowUps(6L)).thenReturn(false);
        when(leadMapper.softDeleteByIdAndOwner(6L, 1L)).thenReturn(1);

        service.delete(6L, 1L);

        verify(followUpService).hasFollowUps(6L);
        verify(leadMapper).softDeleteByIdAndOwner(6L, 1L);
    }

    @Test
    @DisplayName("删除正常（影响行数=1）：带归属条件，不抛异常")
    void delete_success() {
        when(leadAccessService.getOperable(6L, 1L)).thenReturn(lead(6L, null));
        when(leadMapper.softDeleteByIdAndOwner(6L, 1L)).thenReturn(1);

        service.delete(6L, 1L);

        verify(leadMapper).softDeleteByIdAndOwner(6L, 1L);
    }

    @Test
    @DisplayName("竞态：重抽影响行数为 0（归属已被他人变更）→ 抛异常，不重算评分")
    void reextract_zeroRowsThrows() {
        Lead lead = lead(8L, null);
        lead.setOriginalText("客户想要采购一批设备");
        when(leadAccessService.getOperable(8L, 1L)).thenReturn(lead);
        when(leadExtractService.isModelConfigured()).thenReturn(false);
        when(leadExtractService.extract(lead, 1L, null))
                .thenReturn(new ExtractOutcome(ExtractStatus.DEGRADED, "无 key 降级"));
        when(leadMapper.update(any(), any())).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () -> service.reextract(8L, 1L));
        assertEquals(ResultCode.ILLEGAL_STATUS_TRANSITION, ex.getResultCode());
        verify(scoringService, never()).score(any());
    }

    @Test
    @DisplayName("重抽正常（影响行数=1）：落库并重算评分")
    void reextract_success() {
        Lead lead = lead(8L, null);
        lead.setOriginalText("客户想要采购一批设备");
        when(leadAccessService.getOperable(8L, 1L)).thenReturn(lead);
        when(leadExtractService.isModelConfigured()).thenReturn(false);
        when(leadExtractService.extract(lead, 1L, null))
                .thenReturn(new ExtractOutcome(ExtractStatus.DEGRADED, "无 key 降级"));
        when(leadMapper.update(any(), any())).thenReturn(1);
        when(leadAccessService.getExisting(8L)).thenReturn(lead);

        assertNotNull(service.reextract(8L, 1L));
        verify(scoringService).score(lead);
    }
}
