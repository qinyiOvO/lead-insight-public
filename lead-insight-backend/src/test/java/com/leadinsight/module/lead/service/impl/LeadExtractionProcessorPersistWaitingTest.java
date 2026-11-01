package com.leadinsight.module.lead.service.impl;

import com.leadinsight.module.extract.service.LeadExtractService;
import com.leadinsight.module.lead.entity.Lead;
import com.leadinsight.module.lead.enums.ExtractStatus;
import com.leadinsight.module.lead.mapper.LeadMapper;
import com.leadinsight.module.lead.service.ScoringService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 抽取受理的写入范围单测（P-07 / §5.3）。
 *
 * <p>重抽取这一刻实际只有 {@code extract_status} 一列变化，原实现走
 * {@code update(entity, wrapper)} 会把实体全部非 null 字段回写——包括
 * {@code original_text}（MEDIUMTEXT）与 6 棵索引的键列。本测试卡住三点：
 * ① 只调 2 列更新；② 不调整实体更新；③ 影响行数为 0 时如实返回（并发失败由调用方判定）。</p>
 */
class LeadExtractionProcessorPersistWaitingTest {

    private final LeadExtractService leadExtractService = mock(LeadExtractService.class);
    private final LeadMapper leadMapper = mock(LeadMapper.class);
    private final ScoringService scoringService = mock(ScoringService.class);

    private final LeadExtractionProcessor processor =
            new LeadExtractionProcessor(leadExtractService, leadMapper, scoringService);

    private static Lead lead() {
        Lead lead = new Lead();
        lead.setId(7L);
        lead.setOriginalText("TST-原文，禁止在此处回写".repeat(20));
        lead.setExtractStatus(ExtractStatus.FAILED.name());
        return lead;
    }

    @Test
    @DisplayName("P-07：persistWaiting 只下发 extract_status/updated_at 两列更新，不回写整实体")
    void persistWaiting_onlyGuardedTwoColumnUpdate() {
        when(leadMapper.updateExtractStatusGuarded(6L, ExtractStatus.WAITING.name(), 2L)).thenReturn(1);

        int rows = processor.persistWaiting(leadWithId(6L), 2L);

        assertEquals(1, rows);
        verify(leadMapper).updateExtractStatusGuarded(6L, ExtractStatus.WAITING.name(), 2L);
        verify(leadMapper, never()).updateById(any(Lead.class));
        verify(leadMapper, never()).update(any(Lead.class), any());
    }

    @Test
    @DisplayName("§5.3：影响行数为 0（并发认领交错）时如实返回 0，不吞异常也不重试")
    void persistWaiting_returnsZeroOnConcurrentClaim() {
        when(leadMapper.updateExtractStatusGuarded(anyLong(), anyString(), anyLong())).thenReturn(0);

        int rows = processor.persistWaiting(leadWithId(8L), 2L);

        assertEquals(0, rows, "返回 0 由调用方判定并发失败，本方法不得自行兜底");
        verify(leadMapper).updateExtractStatusGuarded(eq(8L), eq(ExtractStatus.WAITING.name()), eq(2L));
    }

    private static Lead leadWithId(Long id) {
        Lead lead = lead();
        lead.setId(id);
        return lead;
    }
}
