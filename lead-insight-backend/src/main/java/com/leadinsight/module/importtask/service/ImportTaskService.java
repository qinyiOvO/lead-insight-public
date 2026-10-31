package com.leadinsight.module.importtask.service;

import com.leadinsight.common.result.PageResult;
import com.leadinsight.module.importtask.dto.BatchImportRequest;
import com.leadinsight.module.importtask.vo.ImportTaskItemVO;
import com.leadinsight.module.importtask.vo.ImportTaskProgressVO;
import com.leadinsight.module.importtask.vo.ImportTaskVO;

import java.util.List;

/**
 * 批量导入任务服务。不依赖任何 HTTP 对象，只接收/返回业务数据。
 */
public interface ImportTaskService {

    /** 创建任务（202 受理）：空白段过滤 → 建任务与明细 → 事务提交后触发异步执行 */
    ImportTaskVO create(BatchImportRequest request, Long userId);

    /** 任务列表（分页，created_at 倒序）。专员仅见本人创建的任务；主管全量只读。 */
    PageResult<ImportTaskVO> page(Integer page, Integer limit, Long userId, String role);

    /** 任务详情（横向越权防护）；不可见抛 40300，不存在抛 40400 */
    ImportTaskVO detail(Long id, Long userId, String role);

    /** 任务进度（前端 2s 轮询）；不可见抛 40300，不存在抛 40400 */
    ImportTaskProgressVO progress(Long id, Long userId, String role);

    /** 逐条处理明细（数组、非分页，覆盖全部条目，前端逐条结果表以此为准）；不可见抛 40300 */
    List<ImportTaskItemVO> listItems(Long id, Long userId, String role);
}
