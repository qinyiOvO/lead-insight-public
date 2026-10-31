package com.leadinsight.module.importtask.service.impl;

import com.leadinsight.common.web.ClientIpContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.leadinsight.common.result.PageResult;
import com.leadinsight.module.importtask.dto.BatchImportRequest;
import com.leadinsight.module.importtask.entity.ImportTask;
import com.leadinsight.module.importtask.entity.ImportTaskItem;
import com.leadinsight.module.importtask.enums.ImportTaskItemStatus;
import com.leadinsight.module.importtask.enums.ImportTaskStatus;
import com.leadinsight.module.importtask.executor.ImportTaskExecutor;
import com.leadinsight.module.importtask.mapper.ImportTaskItemMapper;
import com.leadinsight.module.importtask.mapper.ImportTaskMapper;
import com.leadinsight.module.importtask.service.ImportTaskAccessService;
import com.leadinsight.module.importtask.service.ImportTaskService;
import com.leadinsight.module.importtask.vo.ImportTaskItemVO;
import com.leadinsight.module.importtask.vo.ImportTaskProgressVO;
import com.leadinsight.module.importtask.vo.ImportTaskVO;
import com.leadinsight.module.user.enums.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 批量导入任务服务实现（T3-01）。Controller 只经本服务访问数据，异步执行细节在 ImportTaskExecutor。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportTaskServiceImpl implements ImportTaskService {

    private static final DateTimeFormatter TASK_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final ImportTaskMapper taskMapper;
    private final ImportTaskItemMapper itemMapper;
    private final ImportTaskExecutor importTaskExecutor;
    private final ImportTaskAccessService importTaskAccessService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportTaskVO create(BatchImportRequest request, Long userId) {
        // 批复②：空白段过滤、不计 total、全空白 40001、200 上限按过滤后数校验
        List<String> segments = ImportTaskRules.filterSegments(request.getTextList());
        ImportTaskRules.validateSegmentCount(segments.size());

        ImportTask task = new ImportTask();
        task.setTaskNo("IMPT" + LocalDateTime.now().format(TASK_NO_FORMAT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000)));
        task.setImportSource(request.sourceOrDefault().name());
        task.setTotalCount(segments.size());
        task.setStatus(ImportTaskStatus.PENDING.name());
        task.setCreatedBy(userId);
        // 显式初始化计数与时间：insert 后 MyBatis-Plus 不回填 DB 默认值，
        // 202 响应体直接由本实体装配，必须带完整初值（0 / now）
        task.setProcessedCount(0);
        task.setSuccessCount(0);
        task.setDuplicateCount(0);
        task.setFailCount(0);
        task.setCreatedAt(LocalDateTime.now());
        taskMapper.insert(task);

        // 明细行与任务同事务落库（seq 从 1 起，与提交顺序一致）；条目原文不落库，经内存传给执行器
        for (int i = 0; i < segments.size(); i++) {
            ImportTaskItem item = new ImportTaskItem();
            item.setTaskId(task.getId());
            item.setSeq(i + 1);
            item.setItemStatus(ImportTaskItemStatus.PENDING.name());
            itemMapper.insert(item);
        }

        // 来源 IP 在请求线程内捕获：异步执行时 ClientIpContext 已随请求结束清除
        String clientIp = ClientIpContext.current();
        ImportTaskVO vo = ImportTaskVO.from(task);
        // J-1 同款事务边界硬约束：异步任务只在事务提交后触发，绝不允许读到未提交的任务行
        Long taskId = task.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                importTaskExecutor.process(taskId, segments, userId, clientIp);
            }
        });
        log.info("批量导入任务受理: taskId={} taskNo={} total={} userId={}",
                taskId, task.getTaskNo(), segments.size(), userId);
        return vo;
    }

    @Override
    public PageResult<ImportTaskVO> page(Integer page, Integer limit, Long userId, String role) {
        int pageNo = (page == null || page < 1) ? 1 : page;
        int pageSize = (limit == null || limit < 1) ? 20 : Math.min(limit, 100);
        LambdaQueryWrapper<ImportTask> wrapper = Wrappers.<ImportTask>lambdaQuery()
                .orderByDesc(ImportTask::getId);
        // 读侧可见范围：专员仅见本人创建的任务，主管全量只读
        if (UserRole.fromCode(role) != UserRole.SUPERVISOR) {
            wrapper.eq(ImportTask::getCreatedBy, userId);
        }
        IPage<ImportTask> result = taskMapper.selectPage(new Page<>(pageNo, pageSize), wrapper);
        List<ImportTaskVO> items = result.getRecords().stream().map(ImportTaskVO::from).toList();
        return PageResult.of(items, result.getTotal(), pageNo, pageSize);
    }

    @Override
    public ImportTaskVO detail(Long id, Long userId, String role) {
        return ImportTaskVO.from(importTaskAccessService.getVisible(id, userId, role));
    }

    @Override
    public ImportTaskProgressVO progress(Long id, Long userId, String role) {
        return ImportTaskProgressVO.from(importTaskAccessService.getVisible(id, userId, role));
    }

    @Override
    public List<ImportTaskItemVO> listItems(Long id, Long userId, String role) {
        importTaskAccessService.getVisible(id, userId, role);
        LambdaQueryWrapper<ImportTaskItem> wrapper = Wrappers.<ImportTaskItem>lambdaQuery()
                .eq(ImportTaskItem::getTaskId, id)
                .orderByAsc(ImportTaskItem::getSeq);
        return itemMapper.selectList(wrapper).stream().map(ImportTaskItemVO::from).toList();
    }
}
