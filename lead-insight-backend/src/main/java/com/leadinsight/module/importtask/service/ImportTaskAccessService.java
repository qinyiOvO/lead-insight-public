package com.leadinsight.module.importtask.service;

import com.leadinsight.module.importtask.entity.ImportTask;

/**
 * 导入任务访问守卫：统一的「存在性」与「可见性」校验。
 *
 * <p>独立成 Service 的理由与 {@code LeadAccessService} 一致：lead 模块的
 * {@code LeadService.pageByImportTask} 需要按任务归属做可见性校验，而 importtask 模块又不持有
 * lead 侧逻辑，双方都只依赖本守卫（守卫只依赖 ImportTaskMapper，无反向依赖）即打破循环依赖。</p>
 */
public interface ImportTaskAccessService {

    /** 按主键取任务，不存在抛 40400 */
    ImportTask getExisting(Long taskId);

    /**
     * 取「当前用户可见」的任务（读侧横向越权防护）。
     *
     * <p>规则：{@code SUPERVISOR} 全量只读放行；{@code SPECIALIST} 仅本人创建
     * （created_by = 当前用户）的任务可见，否则抛 40300。role 为空/无法识别时按最不利口径拒绝。</p>
     */
    ImportTask getVisible(Long taskId, Long userId, String role);
}
