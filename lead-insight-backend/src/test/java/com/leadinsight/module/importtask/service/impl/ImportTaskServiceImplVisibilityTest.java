package com.leadinsight.module.importtask.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.leadinsight.common.exception.BizException;
import com.leadinsight.common.result.ResultCode;
import com.leadinsight.module.importtask.entity.ImportTask;
import com.leadinsight.module.importtask.executor.ImportTaskExecutor;
import com.leadinsight.module.importtask.mapper.ImportTaskItemMapper;
import com.leadinsight.module.importtask.mapper.ImportTaskMapper;
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
 * 导入任务读侧可见性单测（横向越权防护）：导入任务是"我的"动作——
 * 专员仅见/可读本人创建的任务；主管全量只读。列表按 created_by 限定，详情/进度/明细走可见性守卫。
 * 本测试用真实 {@link ImportTaskAccessServiceImpl}（仅打桩 mapper），覆盖守卫逻辑本身。
 */
class ImportTaskServiceImplVisibilityTest {

    private final ImportTaskMapper taskMapper = mock(ImportTaskMapper.class);
    private final ImportTaskItemMapper itemMapper = mock(ImportTaskItemMapper.class);
    private final ImportTaskExecutor importTaskExecutor = mock(ImportTaskExecutor.class);

    private final ImportTaskServiceImpl service = new ImportTaskServiceImpl(
            taskMapper, itemMapper, importTaskExecutor,
            new ImportTaskAccessServiceImpl(taskMapper));

    static ImportTask task(long id, Long createdBy) {
        ImportTask task = new ImportTask();
        task.setId(id);
        task.setCreatedBy(createdBy);
        return task;
    }

    @BeforeAll
    static void initTableInfo() {
        // 纯单测无 Spring/MP 启动流程，lambda → 列名 解析需要 TableInfo 缓存
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ImportTask.class);
    }

    @SuppressWarnings("unchecked")
    private AbstractWrapper<ImportTask, ?, ?> capturePageWrapper() {
        ArgumentCaptor<Wrapper<ImportTask>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(taskMapper, atLeastOnce()).selectPage(any(Page.class), captor.capture());
        return (AbstractWrapper<ImportTask, ?, ?>) captor.getValue();
    }

    // ---------------------------------------------------------- 列表

    @Test
    @DisplayName("专员任务列表：按 created_by = 本人 限定")
    void page_specialistScopedToOwn() {
        when(taskMapper.selectPage(any(Page.class), any())).thenAnswer(inv -> inv.getArgument(0));

        service.page(1, 20, 7L, "SPECIALIST");

        AbstractWrapper<ImportTask, ?, ?> wrapper = capturePageWrapper();
        assertTrue(wrapper.getSqlSegment().toLowerCase().contains("created_by"),
                "专员任务列表必须带 created_by 限定");
        assertTrue(wrapper.getParamNameValuePairs().values().contains(7L), "必须绑定当前用户 id");
    }

    @Test
    @DisplayName("主管任务列表：不加 created_by 限定（全量只读）")
    void page_supervisorUnscoped() {
        when(taskMapper.selectPage(any(Page.class), any())).thenAnswer(inv -> inv.getArgument(0));

        service.page(1, 20, 7L, "SUPERVISOR");

        String sql = capturePageWrapper().getSqlSegment().toLowerCase();
        assertTrue(!sql.contains("created_by"), "主管任务列表不得追加 created_by 限定，实际 SQL=" + sql);
    }

    // ---------------------------------------------------------- 详情 / 进度 / 明细

    @Test
    @DisplayName("专员读他人任务详情：抛 FORBIDDEN(403)")
    void detail_forbiddenForOthersTask() {
        when(taskMapper.selectById(20L)).thenReturn(task(20L, 2L));

        BizException ex = assertThrows(BizException.class,
                () -> service.detail(20L, 1L, "SPECIALIST"));
        assertEquals(ResultCode.FORBIDDEN, ex.getResultCode());
        assertEquals(403, ex.getResultCode().getHttpStatus());
    }

    @Test
    @DisplayName("专员读本人任务详情：放行")
    void detail_allowedForOwnTask() {
        when(taskMapper.selectById(21L)).thenReturn(task(21L, 1L));
        assertNotNull(service.detail(21L, 1L, "SPECIALIST"));
    }

    @Test
    @DisplayName("主管读他人任务详情：放行（全量只读）")
    void detail_allowedForSupervisor() {
        when(taskMapper.selectById(22L)).thenReturn(task(22L, 2L));
        assertNotNull(service.detail(22L, 1L, "SUPERVISOR"));
    }

    @Test
    @DisplayName("专员读他人任务进度：抛 FORBIDDEN(403)")
    void progress_forbiddenForOthersTask() {
        when(taskMapper.selectById(23L)).thenReturn(task(23L, 2L));

        BizException ex = assertThrows(BizException.class,
                () -> service.progress(23L, 1L, "SPECIALIST"));
        assertEquals(ResultCode.FORBIDDEN, ex.getResultCode());
    }

    @Test
    @DisplayName("专员读他人任务明细：抛 FORBIDDEN(403) 且不查条目表")
    void itemsForbiddenForOthersTask() {
        when(taskMapper.selectById(24L)).thenReturn(task(24L, 2L));

        BizException ex = assertThrows(BizException.class,
                () -> service.listItems(24L, 1L, "SPECIALIST"));
        assertEquals(ResultCode.FORBIDDEN, ex.getResultCode());
        verify(itemMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("任务不存在：抛 NOT_FOUND(404)")
    void missingTaskNotFound() {
        when(taskMapper.selectById(99L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> service.detail(99L, 1L, "SPECIALIST"));
        assertEquals(ResultCode.NOT_FOUND, ex.getResultCode());
    }
}
