package com.leadinsight.module.lead.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.leadinsight.module.lead.dto.LeadQueryRequest;
import com.leadinsight.module.lead.entity.Lead;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-06 归并构件的护栏单测（依据 {@code docs/02-架构/D06-OR条件拆分改写方案-v1.0.md} §四、§六-V3）。
 *
 * <p>本方案把「一次带 {@code OR} 的查询」拆成两段各自有序扫描后在内存归并。
 * 归并用的比较器<b>必须与 SQL 的 ORDER BY 逐键一致</b>——不一致时页面不会报错，
 * 只会出现"翻页时同分记录跳动"这种极难复现的错觉，所以卡在单测里。</p>
 */
class LeadQuerySupportMergeTest {

    /**
     * 纯单测没有 Spring 上下文，MyBatis-Plus 的 lambda→列名缓存未初始化，
     * {@code eq(Lead::getOwnerId, ...)} 会抛 "can not find lambda cache"。
     * 手动建一次 TableInfo 即可（不影响运行期行为）。
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Lead.class);
    }

    private static Lead lead(long id, int score, String createdAt) {
        Lead lead = new Lead();
        lead.setId(id);
        lead.setPriorityScore(score);
        lead.setCreatedAt(LocalDateTime.parse(createdAt));
        return lead;
    }

    private static LeadQueryRequest query(String sort, String order) {
        LeadQueryRequest request = new LeadQueryRequest();
        request.setSort(sort);
        request.setOrder(order);
        return request;
    }

    // ---------------------------------------------------------------- comparatorFor（V3）

    @Test
    @DisplayName("V3：优先级降序时，同分仍按 created_at 升序（不随 order 翻转）")
    void comparatorFor_priorityDesc_createdAtStaysAsc() {
        Lead older = lead(2L, 80, "2026-10-01T10:00:00");
        Lead newer = lead(1L, 80, "2026-10-05T10:00:00");
        Comparator<Lead> c = LeadQuerySupport.comparatorFor(query(null, "desc"));

        // 分数相同时，created_at 早的排在前——与 applySort 的 orderByAsc(createdAt) 一致
        assertTrue(c.compare(older, newer) < 0, "同分时应按 created_at 升序，早的在前");
        assertTrue(c.compare(newer, older) > 0, "反向比较应取正值");
    }

    @Test
    @DisplayName("V3：优先级升序时，低分在前，同分仍按 created_at 升序")
    void comparatorFor_priorityAsc() {
        Lead low = lead(1L, 30, "2026-10-05T10:00:00");
        Lead high = lead(2L, 90, "2026-10-01T10:00:00");
        Comparator<Lead> c = LeadQuerySupport.comparatorFor(query(null, "asc"));

        assertTrue(c.compare(low, high) < 0, "升序时低分在前");
        assertTrue(c.compare(high, low) > 0, "反向比较应取正值");
    }

    @Test
    @DisplayName("V3：created_at 降序时，created_at 与 id 双向翻转")
    void comparatorFor_createdAtDesc_bothKeysReversed() {
        Lead older = lead(9L, 50, "2026-10-01T10:00:00");
        Lead newer = lead(1L, 50, "2026-10-05T10:00:00");
        Comparator<Lead> c = LeadQuerySupport.comparatorFor(query("created_at", "desc"));

        assertTrue(c.compare(newer, older) < 0, "降序时晚创建在前");
        // 同一时刻：id 也倒序
        Lead a = lead(1L, 50, "2026-10-01T10:00:00");
        Lead b = lead(2L, 50, "2026-10-01T10:00:00");
        assertTrue(c.compare(b, a) < 0, "同时刻降序时 id 大者在前");
    }

    @Test
    @DisplayName("V3：全序性——除 id 外完全相同，比较器仍给出确定结果（id 兜底）")
    void comparatorFor_isTotalOrder_byId() {
        Lead a = lead(1L, 80, "2026-10-01T10:00:00");
        Lead b = lead(2L, 80, "2026-10-01T10:00:00");
        Comparator<Lead> c = LeadQuerySupport.comparatorFor(query(null, "desc"));

        assertTrue(c.compare(a, b) < 0, "非全序会导致归并结果不稳定，id 必须兜底");
        assertTrue(c.compare(b, a) > 0);
        assertEquals(0, c.compare(a, a), "自反性：同一对象比较为 0");
    }

    @Test
    @DisplayName("V3：created_at 排序下 id 也是兜底键（不能只比时间）")
    void comparatorFor_createdAtSort_hasIdTieBreaker() {
        Lead a = lead(1L, 10, "2026-10-01T10:00:00");
        Lead b = lead(2L, 99, "2026-10-01T10:00:00");
        Comparator<Lead> c = LeadQuerySupport.comparatorFor(query("created_at", "asc"));

        // 同时间不同分数：按 created_at 排序时分数不参与，只剩 id 决定
        assertEquals(-1, Integer.signum(c.compare(a, b)), "created_at 相同时必须由 id 决定顺序");
    }

    // ---------------------------------------------------------------- applyOwnerBranch

    @Test
    @DisplayName("D-06：ownerId 为 null → 未认领池段（owner_id IS NULL）")
    void applyOwnerBranch_nullMeansUnclaimedPool() {
        LambdaQueryWrapper<Lead> wrapper = com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery();
        LeadQuerySupport.applyOwnerBranch(wrapper, null);

        String sql = wrapper.getSqlSegment();
        assertNotNull(sql);
        assertTrue(sql.replace("`", "").contains("owner_id IS NULL"),
                "null 分支必须产出 owner_id IS NULL，实际=" + sql);
    }

    @Test
    @DisplayName("D-06：ownerId 非 null → 本人认领段（owner_id = ?）")
    void applyOwnerBranch_valueMeansOwnLeads() {
        LambdaQueryWrapper<Lead> wrapper = com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery();
        LeadQuerySupport.applyOwnerBranch(wrapper, 7L);

        String sql = wrapper.getSqlSegment().replace("`", "");
        assertTrue(sql.contains("owner_id ="), "非 null 分支必须产出 owner_id =，实际=" + sql);
        assertFalse(sql.contains("owner_id IS NULL"), "非 null 分支不得出现 IS NULL，实际=" + sql);
    }

    // ---------------------------------------------------------------- 生效条件（§3.5 / E-02）

    @Test
    @DisplayName("E-02：带状态 / 类别 / 负责人 / 关键词任一条件时视为有残留筛选，不启用拆分")
    void hasResidualFilter_detectsEveryFilter() {
        assertFalse(LeadQuerySupport.hasResidualFilter(new LeadQueryRequest()),
                "纯默认查询（无筛选）应允许拆分");

        LeadQueryRequest byStatus = new LeadQueryRequest();
        byStatus.setStatus(com.leadinsight.module.lead.enums.LeadStatus.NEW);
        assertTrue(LeadQuerySupport.hasResidualFilter(byStatus), "status 属于残留筛选");

        LeadQueryRequest byCategory = new LeadQueryRequest();
        byCategory.setIntentCategory(com.leadinsight.module.lead.enums.IntentCategory.QUOTE);
        assertTrue(LeadQuerySupport.hasResidualFilter(byCategory), "intentCategory 属于残留筛选");

        LeadQueryRequest byOwner = new LeadQueryRequest();
        byOwner.setOwnerId(1L);
        assertTrue(LeadQuerySupport.hasResidualFilter(byOwner), "ownerId 属于残留筛选");

        LeadQueryRequest byKeyword = new LeadQueryRequest();
        byKeyword.setQ("   ");
        assertFalse(LeadQuerySupport.hasResidualFilter(byKeyword), "空白关键词不算筛选条件");

        byKeyword.setQ("报价");
        assertTrue(LeadQuerySupport.hasResidualFilter(byKeyword), "非空关键词属于残留筛选");
    }

    // ---------------------------------------------------------------- 计数投影（§十 E-01）
    @Test
    @DisplayName("E-01：buildConditions 不带列投影，避免生成 COUNT(列,列,...) 非法 SQL")
    void buildConditions_hasNoColumnProjection() {
        LeadQueryRequest request = new LeadQueryRequest();
        request.setStatus(com.leadinsight.module.lead.enums.LeadStatus.NEW);
        request.setQ("测试");

        LambdaQueryWrapper<Lead> wrapper = LeadQuerySupport.buildConditions(request);

        String select = wrapper.getSqlSelect();
        assertTrue(select == null || select.isBlank(),
                "计数 wrapper 必须无列投影，否则 selectCount 会拼出 COUNT(列清单)，实际 select=" + select);
        // 条件本身不能丢：状态筛选与关键词 OR 组都还在
        String sql = wrapper.getSqlSegment().replace("`", "");
        assertTrue(sql.contains("status ="), "状态条件不得丢失，实际=" + sql);
        assertTrue(sql.contains("OR"), "关键词三字段 OR 条件不得丢失，实际=" + sql);
    }

    @Test
    @DisplayName("E-01：buildWrapper 在 buildConditions 之上加投影，两者条件一致")
    void buildWrapper_addsProjectionOnSameConditions() {
        LeadQueryRequest request = new LeadQueryRequest();
        request.setStatus(com.leadinsight.module.lead.enums.LeadStatus.NEW);

        LambdaQueryWrapper<Lead> plain = LeadQuerySupport.buildConditions(request);
        LambdaQueryWrapper<Lead> projected = LeadQuerySupport.buildWrapper(request);

        assertNotNull(projected.getSqlSelect(), "列表 wrapper 必须有列投影");
        assertEquals(plain.getSqlSegment(), projected.getSqlSegment(), "投影不得改变 WHERE 条件");
    }
}
