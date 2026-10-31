package com.leadinsight.module.lead.service;

import com.leadinsight.common.result.PageResult;
import com.leadinsight.module.lead.dto.LeadCreateRequest;
import com.leadinsight.module.lead.dto.LeadCreationOutcome;
import com.leadinsight.module.lead.dto.LeadQueryRequest;
import com.leadinsight.module.lead.dto.LeadUpdateRequest;
import com.leadinsight.module.lead.dto.StatusChangeRequest;
import com.leadinsight.module.lead.vo.LeadDetailVO;
import com.leadinsight.module.lead.vo.LeadVO;
import com.leadinsight.module.lead.vo.ScoreDetailVO;

import java.util.List;

/**
 * 线索服务。不依赖任何 HTTP 对象，只接收/返回业务数据。
 */
public interface LeadService {

    /** 创建线索（openapi 双模式：手工录入 / 粘贴待抽取），创建后立即评分 */
    LeadVO create(LeadCreateRequest request, Long userId);

    /**
     * 批量导入单条创建（T3-01 亮点2）：与 {@link #create} 复用同一条管线（指纹去重 → 落库 →
     * 抽取 → 评分），落库时覆写 import_source=BATCH 并绑定 import_task_id；抽取强制同步执行
     * （批量任务本身已在后台线程，条目终态须当场判定）。重复内容抛 40901（message 含已存在线索 id）。
     */
    LeadCreationOutcome createFromBatch(String originalText, Long userId, Long importTaskId, String clientIp);

    /** 按导入任务分页列出产出的线索（GET /import-tasks/{id}/leads，按线索 id 升序）；
     *  先校验任务对当前用户可见（横向越权防护） */
    PageResult<LeadVO> pageByImportTask(Long importTaskId, Integer page, Integer limit,
                                        Long userId, String role);

    /** 分页查询（筛选 / 排序 / 搜索）。visible 范围：专员=本人认领+未认领池；主管=全量只读 */
    PageResult<LeadVO> page(LeadQueryRequest query, Long userId, String role);

    /** 详情（含评分明细）；不可见抛 40300 */
    LeadDetailVO detail(Long id, Long userId, String role);

    /** 编辑字段（越权校验），编辑后重算评分 */
    LeadVO update(Long id, LeadUpdateRequest request, Long userId);

    /** 逻辑删除（越权校验） */
    void delete(Long id, Long userId);

    /** 认领：NEW -> FOLLOWING，绑定 owner，并发唯一 */
    LeadVO claim(Long id, Long userId);

    /** 状态迁移（受状态机约束），成功时写一条跟进记录 */
    LeadVO changeStatus(Long id, StatusChangeRequest request, Long userId);

    /**
     * 重新触发抽取（T2-06，openapi `POST /leads/{id}/extract`）：与粘贴创建复用同一条
     * 抽取编排；模型不可用时返回 200 且 extractStatus=DEGRADED（不抛错）。
     */
    LeadVO reextract(Long id, Long userId);

    /** 评分明细；不可见抛 40300 */
    List<ScoreDetailVO> listScores(Long id, Long userId, String role);
}
