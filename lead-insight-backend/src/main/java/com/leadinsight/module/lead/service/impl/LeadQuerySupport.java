package com.leadinsight.module.lead.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.leadinsight.common.util.TextUtils;
import com.leadinsight.module.lead.dto.LeadQueryRequest;
import com.leadinsight.module.lead.entity.Lead;
import com.leadinsight.module.user.enums.UserRole;

/**
 * 线索列表查询条件与排序装配。抽离自 Service 以保持单文件职责单一。
 */
public final class LeadQuerySupport {

    public static final int DEFAULT_PAGE = 1;
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private LeadQuerySupport() {
    }

    public static int clampPage(Integer page) {
        return (page == null || page < 1) ? DEFAULT_PAGE : page;
    }

    public static int clampLimit(Integer limit) {
        return (limit == null || limit < 1) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
    }

    /** 组装筛选与搜索条件（不含排序） */
    public static LambdaQueryWrapper<Lead> buildWrapper(LeadQueryRequest query) {
        LambdaQueryWrapper<Lead> wrapper = Wrappers.lambdaQuery();
        if (query.getStatus() != null) {
            wrapper.eq(Lead::getStatus, query.getStatus().name());
        }
        if (query.getIntentCategory() != null) {
            wrapper.eq(Lead::getIntentCategory, query.getIntentCategory().name());
        }
        if (query.getOwnerId() != null) {
            wrapper.eq(Lead::getOwnerId, query.getOwnerId());
        }
        if (TextUtils.isNotBlank(query.getQ())) {
            // AC-11：客户名 / 公司名 / 需求要点 三字段 OR 模糊匹配
            String keyword = query.getQ().trim();
            wrapper.and(w -> w.like(Lead::getCustomerName, keyword)
                    .or().like(Lead::getCompanyName, keyword)
                    .or().like(Lead::getDemandSummary, keyword));
        }
        return wrapper;
    }

    /**
     * 读侧可见范围强制过滤（PRD §3.3 权限矩阵 / ARCHITECTURE §7 越权防护）。
     *
     * <p>{@code SUPERVISOR} 全量只读——不加任何归属限定；{@code SPECIALIST} 仅见
     * 「本人认领 + 未认领池」，即强制追加 {@code (owner_id = :userId OR owner_id IS NULL)}。
     * role 为空/无法识别时按最不利口径（视为专员）限定。</p>
     *
     * <p>该条件在用户可选筛选项（含 query.ownerId）之外<b>额外</b>叠加，因此专员即便
     * 传入他人 ownerId 也只会得到空集，不会越权。</p>
     */
    public static void applyVisibility(LambdaQueryWrapper<Lead> wrapper, Long userId, String role) {
        if (UserRole.fromCode(role) == UserRole.SUPERVISOR) {
            return;
        }
        wrapper.and(w -> w.eq(Lead::getOwnerId, userId).or().isNull(Lead::getOwnerId));
    }

    /**
     * 排序铁律（AC-18）：按优先级排序时同分一律按创建时间升序，
     * 再以 id 升序兜底，保证分页结果稳定可复现。
     */
    public static void applySort(LambdaQueryWrapper<Lead> wrapper, LeadQueryRequest query) {
        boolean asc = "asc".equalsIgnoreCase(query.getOrder());
        if ("created_at".equalsIgnoreCase(query.getSort())) {
            wrapper.orderBy(true, asc, Lead::getCreatedAt).orderBy(true, asc, Lead::getId);
            return;
        }
        wrapper.orderBy(true, asc, Lead::getPriorityScore)
                .orderByAsc(Lead::getCreatedAt)
                .orderByAsc(Lead::getId);
    }
}
