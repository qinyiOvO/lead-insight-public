package com.leadinsight.module.importtask.service.impl;

import com.leadinsight.common.exception.BizException;
import com.leadinsight.common.result.ResultCode;
import com.leadinsight.module.importtask.entity.ImportTask;
import com.leadinsight.module.importtask.mapper.ImportTaskMapper;
import com.leadinsight.module.importtask.service.ImportTaskAccessService;
import com.leadinsight.module.user.enums.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 导入任务访问守卫实现。只做存在性与归属校验，不含业务规则计算。
 */
@Service
@RequiredArgsConstructor
public class ImportTaskAccessServiceImpl implements ImportTaskAccessService {

    private final ImportTaskMapper taskMapper;

    @Override
    public ImportTask getExisting(Long taskId) {
        if (taskId == null) {
            throw new BizException(ResultCode.NOT_FOUND, "导入任务不存在");
        }
        ImportTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ResultCode.NOT_FOUND, "导入任务不存在");
        }
        return task;
    }

    @Override
    public ImportTask getVisible(Long taskId, Long userId, String role) {
        ImportTask task = getExisting(taskId);
        // 主管全量只读；专员仅本人创建的任务可见（导入任务是"我的"动作）
        if (UserRole.fromCode(role) == UserRole.SUPERVISOR) {
            return task;
        }
        if (!Objects.equals(task.getCreatedBy(), userId)) {
            throw new BizException(ResultCode.FORBIDDEN, "无权查看他人的导入任务");
        }
        return task;
    }
}
