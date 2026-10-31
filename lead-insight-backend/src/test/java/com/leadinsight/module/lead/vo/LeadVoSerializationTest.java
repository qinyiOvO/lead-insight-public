package com.leadinsight.module.lead.vo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leadinsight.module.lead.entity.Lead;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LeadVO v1.2.1 新增字段的序列化契约锁定（纯单元测试，不依赖 Spring 容器与数据库）。
 *
 * <p>刻意用最朴素的 ObjectMapper（甚至保留 WRITE_DATES_AS_TIMESTAMPS 默认 true）来验证
 * 字段级 {@code @JsonFormat} 生效：即便全局 Jackson 配置将来被改坏，{@code expectedTime}
 * 仍必须序列化为 yyyy-MM-dd 字符串，绝不写成 {@code [2026,10,27]} 数组。
 * {@code ownerName} 未认领必须为 null，而非空串 ""。</p>
 */
class LeadVoSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("expectedTime 序列化为 yyyy-MM-dd 字符串（不得为 [y,m,d] 数组）")
    void expectedTimeSerializedAsDateString() throws Exception {
        Lead lead = new Lead();
        lead.setExpectedTime(LocalDate.of(2026, 10, 27));

        String json = mapper.writeValueAsString(LeadVO.from(lead));

        assertTrue(json.contains("\"expectedTime\":\"2026-10-27\""), json);
        assertFalse(json.contains("[2026,10,27]"), json);
    }

    @Test
    @DisplayName("ownerName 未认领时序列化为 null（而非空串）")
    void ownerNameIsNullWhenUnclaimed() throws Exception {
        Lead lead = new Lead(); // ownerId 保持 null = 未认领

        String json = mapper.writeValueAsString(LeadVO.from(lead));

        assertTrue(json.contains("\"ownerName\":null"), json);
        assertFalse(json.contains("\"ownerName\":\"\""), json);
    }
}
