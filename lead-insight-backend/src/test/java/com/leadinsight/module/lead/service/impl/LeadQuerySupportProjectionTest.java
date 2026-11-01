package com.leadinsight.module.lead.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.leadinsight.module.lead.dto.LeadQueryRequest;
import com.leadinsight.module.lead.entity.Lead;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 列表列投影（P-01）等价性单测：投影必须<b>覆盖 LeadVO 消费的全部字段</b>，且<b>不含大字段</b>。
 *
 * <p>这条测试的作用是机械护栏：{@code LeadVO} 新增字段却忘了加进投影时，列表该列恒为 null，
 * 页面表现为空白——靠人眼回归极易漏。此处在 SQL 文本层面卡死"18 列且不含 original_text"。</p>
 */
class LeadQuerySupportProjectionTest {

    /** LeadVO.from 消费的字段数（id/title/customer/company/intent/demand/expectedTime/score/level/reason/status/extractStatus/confidence/confidenceLevel/ownerId/importSource/createdAt/updatedAt） */
    private static final int EXPECTED_COLUMNS = 18;

    @Test
    @DisplayName("P-01：列表 wrapper 投影 18 列，且不含 original_text / contact_info 等大字段")
    void buildWrapper_projectsVoSColumnsOnly() {
        LambdaQueryWrapper<Lead> wrapper = LeadQuerySupport.buildWrapper(new LeadQueryRequest());
        String select = wrapper.getSqlSelect();

        assertEquals(EXPECTED_COLUMNS, select.split(",").length, "投影列数必须与 LeadVO 消费字段一致");
        assertTrue(select.contains("customer_name"), "客户名称必须在投影内，实际=" + select);
        assertTrue(select.contains("updated_at"), "updatedAt（DEF-09）必须在投影内，实际=" + select);
        assertFalse(select.contains("original_text"), "原文正文不得进入列表查询，实际=" + select);
        assertFalse(select.contains("contact_info"), "联系方式列表不消费，不得进入投影，实际=" + select);
        assertFalse(select.contains("keywords_hit"), "命中关键词列表不消费，不得进入投影，实际=" + select);
    }
}
