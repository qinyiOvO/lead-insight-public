package com.leadinsight.module.lead.service;

import com.leadinsight.module.lead.entity.Lead;

/**
 * 线索访问守卫：统一的「存在性」与「可操作性」校验。
 *
 * <p>独立成 Service 是为了打破 lead 与 followup 两个模块之间的循环依赖：
 * followup 需要校验线索归属，lead 需要在状态迁移时写跟进记录，
 * 双方都只依赖本守卫（守卫只依赖 LeadMapper，无反向依赖）。</p>
 */
public interface LeadAccessService {

    /** 按主键取线索（自动排除逻辑删除），不存在抛 40400 */
    Lead getExisting(Long leadId);

    /**
     * 取「当前用户可操作」的线索（AC-21 越权防护）。
     * 规则：owner_id 为空，或 owner_id 等于当前用户，才允许操作；否则抛 40300。
     */
    Lead getOperable(Long leadId, Long userId);

    /**
     * 取「当前用户可见」的线索（读侧横向越权防护，对齐 PRD §3.3 权限矩阵）。
     *
     * <p>规则：{@code SUPERVISOR} 全量只读放行；{@code SPECIALIST} 仅当 owner_id 为空
     * （未认领池）或等于本人时可见，否则抛 40300。role 为空/无法识别时按最不利口径（视为专员）拒绝。</p>
     */
    Lead getVisible(Long leadId, Long userId, String role);
}
