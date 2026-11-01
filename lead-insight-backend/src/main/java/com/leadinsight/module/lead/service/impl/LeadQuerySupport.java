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

    /**
     * 列表出参列投影（P-01 / §5.2-Q01）：只取 {@link com.leadinsight.module.lead.vo.LeadVO}
     * 真正消费的 18 列，剔除 {@code original_text MEDIUMTEXT} 等大字段。
     *
     * <p>语义等价性：{@code LeadVO.from(Lead)} 只读这 18 个属性，未投影字段在 VO 上恒为 null，
     * 输出 JSON 与投影前逐字节相同；{@code ownerName} 走整页 IN 回填，不依赖主查询。
     * 详情页（{@code LeadDetailVO} + {@code selectById}）需要原文，走独立路径，不受影响。</p>
     *
     * <p><b>COUNT 请改用 {@link #buildConditions}</b>：分页插件自动生成的 COUNT 会忽略 SELECT 列表，
     * 但 {@code leadMapper.selectCount(wrapper)} 不会——它把 18 列塞进 {@code COUNT(...)} 生成非法 SQL
     * （D-06 验证期实测 500）。</p>
     *
     * <p><b>新增 LeadVO 字段时必须同步加进本方法</b>，否则列表该列会恒为空。</p>
     */
    public static void applyListProjection(LambdaQueryWrapper<Lead> wrapper) {
        wrapper.select(Lead::getId, Lead::getSubjectTitle, Lead::getCustomerName, Lead::getCompanyName,
                Lead::getIntentCategory, Lead::getDemandSummary, Lead::getExpectedTime,
                Lead::getPriorityScore, Lead::getPriorityLevel, Lead::getScoreReason,
                Lead::getStatus, Lead::getExtractStatus, Lead::getExtractConfidence, Lead::getConfidenceLevel,
                Lead::getOwnerId, Lead::getImportSource, Lead::getCreatedAt, Lead::getUpdatedAt);
    }

    /**
     * 组装筛选与搜索条件（不含排序、<b>不含列投影</b>）。
     *
     * <p>计数专用：{@code selectCount(wrapper)} 会把 wrapper 的 {@code select(...)} 原样拼进
     * {@code COUNT(}，因此计数必须用不带投影的 wrapper。条件逻辑只有本方法一份，
     * {@link #buildWrapper} 在其基础上加投影，二者条件不会漂移。</p>
     */
    public static LambdaQueryWrapper<Lead> buildConditions(LeadQueryRequest query) {
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

    /** 组装筛选与搜索条件（不含排序），含列表列投影。列表查询用这个。 */
    public static LambdaQueryWrapper<Lead> buildWrapper(LeadQueryRequest query) {
        LambdaQueryWrapper<Lead> wrapper = buildConditions(query);
        applyListProjection(wrapper);
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

    // ------------------------------------------------------------------ D-06：OR 条件拆分
    // 依据：docs/02-架构/D06-OR条件拆分改写方案-v1.0.md
    // 专员视角的 (owner_id = :userId OR owner_id IS NULL) 会让优化器放弃有序索引扫描，
    // 退化为「全表取回 + filesort」（10 万行实测 372ms，理论极限 0.14ms）。
    // 故拆成两段各自有序扫描，应用层归并。以下两个方法为拆分的原子构件。

    /**
     * D-06：是否带「残留筛选条件」（状态 / 类别 / 负责人 / 关键词任一）。
     *
     * <p>带残留条件时，优化器在<b>每个分段内部</b>依然放弃有序索引扫描改做全段 Sort
     * （实测 {@code status='FOLLOWING' AND owner_id=1} 一段就 Sort 掉 18 000 行、89 ms），
     * 拆两段等于把一次排序换成两次再加归并，比原路径更慢（10 万行实测 0.45×–0.83×）。
     * 因此这类查询不走拆分，行为与改造前完全一致。</p>
     *
     * <p>依据 {@code docs/02-架构/D06-OR条件拆分改写方案-v1.0.md} §3.5 与 §十 E-02。</p>
     */
    public static boolean hasResidualFilter(LeadQueryRequest query) {
        return query.getStatus() != null
                || query.getIntentCategory() != null
                || query.getOwnerId() != null
                || TextUtils.isNotBlank(query.getQ());
    }

    /**
     * D-06：给拆分段打上 owner 归属条件。
     *
     * <p>{@code ownerId == null} 表示"未认领池"段（{@code owner_id IS NULL}），
     * 非 null 表示"本人认领"段（{@code owner_id = :ownerId}）。
     * 两段并集即 {@link #applyVisibility} 中 {@code OR} 条件的全集。</p>
     */
    public static void applyOwnerBranch(LambdaQueryWrapper<Lead> wrapper, Long ownerId) {
        if (ownerId == null) {
            wrapper.isNull(Lead::getOwnerId);
        } else {
            wrapper.eq(Lead::getOwnerId, ownerId);
        }
    }

    /**
     * D-06：与 {@link #applySort} <b>逐键对应</b>的内存比较器，用于两段结果归并。
     *
     * <p>必须与排序 SQL 完全一致，否则归并后顺序与单查询不同。对应关系：</p>
     * <ul>
     *   <li>{@code sort=created_at}：{@code created_at <dir>, id <dir>}（两键同向翻转）</li>
     *   <li>其余（优先级）：{@code priority_score <dir>, created_at ASC, id ASC}
     *       —— 只有首键随 {@code order} 翻转，后两键恒升序</li>
     * </ul>
     *
     * <p><b>全序保证</b>：两套键组都以唯一键 {@code id} 收尾，故归并结果确定、无并列歧义。
     * 改动 {@code applySort} 时必须同步改本方法（单测 V3 卡这一点）。</p>
     */
    public static java.util.Comparator<Lead> comparatorFor(LeadQueryRequest query) {
        boolean asc = "asc".equalsIgnoreCase(query.getOrder());
        boolean byCreated = "created_at".equalsIgnoreCase(query.getSort());
        return (a, b) -> {
            if (byCreated) {
                int r = a.getCreatedAt().compareTo(b.getCreatedAt());
                if (r != 0) {
                    return asc ? r : -r;
                }
                r = a.getId().compareTo(b.getId());
                return asc ? r : -r;
            }
            int r = Integer.compare(a.getPriorityScore(), b.getPriorityScore());
            if (r != 0) {
                return asc ? r : -r;
            }
            r = a.getCreatedAt().compareTo(b.getCreatedAt());
            if (r != 0) {
                return r;                       // 同分按创建时间升序，不随 order 翻转
            }
            return a.getId().compareTo(b.getId());  // id 升序兜底
        };
    }
}
