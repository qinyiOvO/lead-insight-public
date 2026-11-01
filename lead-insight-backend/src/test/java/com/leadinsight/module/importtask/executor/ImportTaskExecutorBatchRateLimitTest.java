package com.leadinsight.module.importtask.executor;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.leadinsight.module.importtask.entity.ImportTask;
import com.leadinsight.module.importtask.entity.ImportTaskItem;
import com.leadinsight.module.importtask.enums.ImportTaskItemStatus;
import com.leadinsight.module.importtask.mapper.ImportTaskItemMapper;
import com.leadinsight.module.importtask.mapper.ImportTaskMapper;
import com.leadinsight.module.lead.dto.LeadCreationOutcome;
import com.leadinsight.module.lead.enums.ExtractStatus;
import com.leadinsight.module.lead.service.LeadService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批量任务单条限流场景（走查行 6 补测，R3 关单项）。
 *
 * <p>限流在管线里的可观测终点：LeadExtractService.extract 对限流（429/配额）永不抛异常，
 * 而是折算 ExtractOutcome(DEGRADED, 限流文案)（J-2：model_call_log 落行由该编排负责，
 * 其用例在 LeadExtractPipelineTest——批量复用同一条管线，本类只验批量层语义）。
 * stub LeadService.createFromBatch 返回该折算终点，验证：
 * ①限流条 item=DEGRADED + reason 可读 ②后续条目照常处理（批次不中断）
 * ③任务计数落 manual_count 桶（DEF-04：转人工单列，不再计入 fail_count）
 * ④任务终态按 DEF-04 四桶口径（全降级→PARTIAL / 混合→PARTIAL / 全成功→SUCCESS）。</p>
 */
class ImportTaskExecutorBatchRateLimitTest {

    private static final String RATE_LIMIT_REASON = "模型服务限流(429)，本次抽取已降级为待人工处理，线索已正常入库";

    private ImportTaskMapper taskMapper;
    private ImportTaskItemMapper itemMapper;
    private LeadService leadService;
    private ImportTaskExecutor executor;

    @BeforeEach
    void setUp() {
        // 纯单测无 Spring/MP 启动流程，lambdaUpdate 需要 TableInfo 缓存，手工初始化
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ImportTask.class);
        TableInfoHelper.initTableInfo(assistant, ImportTaskItem.class);

        taskMapper = mock(ImportTaskMapper.class);
        itemMapper = mock(ImportTaskItemMapper.class);
        leadService = mock(LeadService.class);
        when(taskMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(itemMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        executor = new ImportTaskExecutor(taskMapper, itemMapper, leadService);
    }

    private ImportTask taskWithCounts(int success, int duplicate, int manual, int fail) {
        ImportTask task = new ImportTask();
        task.setId(7L);
        task.setTotalCount(2);
        task.setProcessedCount(2);
        task.setSuccessCount(success);
        task.setDuplicateCount(duplicate);
        task.setManualCount(manual);
        task.setFailCount(fail);
        task.setStatus("RUNNING");
        return task;
    }

    @Test
    @DisplayName("批量限流：条1限流DEGRADED、条2照常处理，全降级终态PARTIAL（DEF-04 转人工单列）")
    void batchRateLimit_allLimited_degradedTaskPartial() {
        when(leadService.createFromBatch(any(), any(), any(), any())).thenReturn(
                new LeadCreationOutcome(101L, null, ExtractStatus.DEGRADED, RATE_LIMIT_REASON),
                new LeadCreationOutcome(102L, null, ExtractStatus.DEGRADED, RATE_LIMIT_REASON));
        when(taskMapper.selectById(7L)).thenReturn(taskWithCounts(0, 0, 2, 0));

        executor.process(7L, List.of("邮件一", "邮件二"), 9L, null);

        // ② 第 2 条仍被处理：两条都到达创建管线（批次不因单条限流中断）
        verify(leadService, times(2)).createFromBatch(any(), any(), any(), any());
        // ① 每条各有一次 RUNNING 置位与一次终态落行，限流条终态=DEGRADED 且 reason 可读
        verify(itemMapper, atLeast(4)).update(any(), any(Wrapper.class));
        ArgumentCaptor<Wrapper<ImportTaskItem>> itemCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(itemMapper, atLeast(4)).update(any(), itemCaptor.capture());
        String allItemParams = joinParamValues(itemCaptor.getAllValues());
        assertTrue(allItemParams.contains("DEGRADED"), "限流条目必须落 DEGRADED");
        assertTrue(allItemParams.contains(RATE_LIMIT_REASON), "reason 必须携带可读限流文案");
        // ③ DEF-04：限流降级计数落 MANUAL 桶（转人工单列，不再计入 FAIL 桶）；
        //    P-10 改造后计数走 bumpCounters 的增量参数，故直接断言增量数值而不是 SQL 文本：
        //    processed+2 / success+0 / duplicate+0 / manual+2 / fail+0
        //    全降级时线索其实均已入库 → 任务终态 PARTIAL，不得判 FAILED（K-1 Amendment）
        verify(taskMapper).bumpCounters(eq(7L), eq(2), eq(0), eq(0), eq(2), eq(0));
        ArgumentCaptor<Wrapper<ImportTask>> taskCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(taskMapper, atLeast(2)).update(any(), taskCaptor.capture());
        String taskParams = joinParamValues(taskCaptor.getAllValues());
        assertTrue(taskParams.contains("PARTIAL"), "全部条目转人工时任务终态必须为 PARTIAL");
        assertFalse(taskParams.contains("FAILED"),
                "全部条目转人工（线索均已入库）不得判 FAILED");
    }

    @Test
    @DisplayName("批量限流：条1限流DEGRADED、条2正常SUCCESS，混合终态PARTIAL")
    void batchRateLimit_mixedLimitedAndSuccess_taskPartial() {
        when(leadService.createFromBatch(any(), any(), any(), any())).thenReturn(
                new LeadCreationOutcome(201L, null, ExtractStatus.DEGRADED, RATE_LIMIT_REASON),
                new LeadCreationOutcome(202L, "张三", ExtractStatus.SUCCESS, null));
        when(taskMapper.selectById(7L)).thenReturn(taskWithCounts(1, 0, 1, 0));

        executor.process(7L, List.of("邮件一", "邮件二"), 9L, null);

        verify(leadService, times(2)).createFromBatch(any(), any(), any(), any());
        // 四桶守恒（P-10 后仍成立）：混合场景 processed+2 / success+1 / manual+1 / 其余 0
        verify(taskMapper).bumpCounters(eq(7L), eq(2), eq(1), eq(0), eq(1), eq(0));
        // High-2 回归锚定：必须断言"写进 import_task_item 的**数据**"，不能只验状态/文案——
        // 漏设 setLeadId/setCustomerName 时 item_status 仍是 SUCCESS、reason 仍是 null，状态型断言照绿。
        ArgumentCaptor<Wrapper<ImportTaskItem>> itemCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(itemMapper, atLeast(4)).update(any(), itemCaptor.capture());
        String itemParams = joinParamValues(itemCaptor.getAllValues());
        assertTrue(itemParams.contains("202"),
                "SUCCESS 条目必须把 leadId(202) 写进明细，否则逐条结果无法追溯线索");
        assertTrue(itemParams.contains("张三"),
                "SUCCESS 条目必须把 customerName(张三) 写进明细");
        ArgumentCaptor<Wrapper<ImportTask>> taskCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(taskMapper, atLeast(2)).update(any(), taskCaptor.capture());
        assertTrue(joinParamValues(taskCaptor.getAllValues()).contains("PARTIAL"),
                "限流降级与成功混合时任务终态必须为 PARTIAL");
    }

    @Test
    @DisplayName("批量全成功：每条明细必须回填 leadId/customerName（High-2 回归锚定）")
    void batchAllSuccess_itemsCarryLeadIdAndCustomerName() {
        when(leadService.createFromBatch(any(), any(), any(), any())).thenReturn(
                new LeadCreationOutcome(301L, "李四", ExtractStatus.SUCCESS, null),
                new LeadCreationOutcome(302L, "王五", ExtractStatus.SUCCESS, null));
        when(taskMapper.selectById(7L)).thenReturn(taskWithCounts(2, 0, 0, 0));

        executor.process(7L, List.of("邮件一", "邮件二"), 9L, null);

        // 只验状态不够：SUCCESS 分支漏设 setLeadId/setCustomerName 时 item_status 仍为 SUCCESS，
        // 必须断言写入明细的参数里带上了 leadId 与 customerName（逐条结果可追溯线索）
        ArgumentCaptor<Wrapper<ImportTaskItem>> itemCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(itemMapper, atLeast(4)).update(any(), itemCaptor.capture());
        String itemParams = joinParamValues(itemCaptor.getAllValues());
        assertTrue(itemParams.contains("301"), "条1 SUCCESS 必须回填 leadId(301)");
        assertTrue(itemParams.contains("302"), "条2 SUCCESS 必须回填 leadId(302)");
        assertTrue(itemParams.contains("李四"), "条1 SUCCESS 必须回填 customerName(李四)");
        assertTrue(itemParams.contains("王五"), "条2 SUCCESS 必须回填 customerName(王五)");
        // 全成功 → 任务终态 SUCCESS（K-1：fail=0）
        // 四桶守恒：全成功 processed+2 / success+2 / 其余三桶 0
        verify(taskMapper).bumpCounters(eq(7L), eq(2), eq(2), eq(0), eq(0), eq(0));
        ArgumentCaptor<Wrapper<ImportTask>> taskCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(taskMapper, atLeast(2)).update(any(), taskCaptor.capture());
        String taskParams = joinParamValues(taskCaptor.getAllValues());
        assertTrue(taskParams.contains("SUCCESS"), "全成功任务的终态必须为 SUCCESS");
        assertFalse(taskParams.contains(ImportTaskItemStatus.FAILED.name()),
                "全成功任务不得出现 FAILED 状态值");
    }

    /** 取 wrapper 中绑定的参数值（状态枚举/reason 文案都在 paramNameValuePairs 里） */
    @SuppressWarnings("unchecked")
    private String joinParamValues(Collection<? extends Wrapper<?>> wrappers) {
        return wrappers.stream()
                .map(w -> (AbstractWrapper<Object, Object, ?>) w)
                .map(AbstractWrapper::getParamNameValuePairs)
                .map(Map::values)
                .flatMap(Collection::stream)
                .map(String::valueOf)
                .reduce((a, b) -> a + "|" + b)
                .orElse("");
    }
}
