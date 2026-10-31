package com.leadinsight.module.extract.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型白名单闸单测（用户硬规则：只准 deepseek-flash，禁用 pro 等高价模型）。
 *
 * <p>纯单元测试，直接 {@code new LlmProperties()}，不启动 Spring 上下文。
 * 证明闸真的拦得住：配成任何白名单外的模型（含 null）都会 fail-fast，
 * 而 flash（含前后空格）放行。校验只针对 model，与 apiKey 无关。</p>
 */
class LlmPropertiesModelWhitelistTest {

    @Test
    @DisplayName("model=deepseek-flash → 放行")
    void allowsFlash() {
        LlmProperties properties = new LlmProperties();
        properties.setModel("deepseek-flash");
        assertDoesNotThrow(properties::validateModel, "白名单内的 deepseek-flash 必须放行");
    }

    @Test
    @DisplayName("model=deepseek-v4-pro → 拦截，报错含模型名（成本红线）")
    void rejectsPro() {
        LlmProperties properties = new LlmProperties();
        properties.setModel("deepseek-v4-pro");
        IllegalStateException ex = assertThrows(IllegalStateException.class, properties::validateModel,
                "高价 pro 模型必须被白名单拦截");
        assertTrue(ex.getMessage().contains("deepseek-v4-pro"),
                "报错信息应包含被拒的模型名，便于定位；实际=" + ex.getMessage());
    }

    @Test
    @DisplayName("model=deepseek-chat → 拦截")
    void rejectsOtherModel() {
        LlmProperties properties = new LlmProperties();
        properties.setModel("deepseek-chat");
        assertThrows(IllegalStateException.class, properties::validateModel,
                "任何白名单外的模型都必须被拦截");
    }

    @Test
    @DisplayName("model=\"  deepseek-flash  \"（前后空格）→ trim 后放行")
    void trimsWhitespace() {
        LlmProperties properties = new LlmProperties();
        properties.setModel("  deepseek-flash  ");
        assertDoesNotThrow(properties::validateModel, "trim 生效后前后空格不应导致误拦");
    }

    @Test
    @DisplayName("model=null → 拦截（不得 NPE 或静默通过）")
    void rejectsNull() {
        LlmProperties properties = new LlmProperties();
        properties.setModel(null);
        IllegalStateException ex = assertThrows(IllegalStateException.class, properties::validateModel,
                "null 模型必须被拦截，且不得抛 NPE");
        assertTrue(ex.getMessage().contains("【模型白名单】"),
                "null 情况也应命中白名单错误文案；实际=" + ex.getMessage());
    }

    @Test
    @DisplayName("校验与 apiKey 无关：无 key + 合法模型 依然放行（不破坏无 key 铁律）")
    void whitelistIndependentOfApiKey() {
        LlmProperties properties = new LlmProperties();
        properties.setApiKey("");
        properties.setModel("deepseek-flash");
        assertEquals("", properties.getApiKey(), "前置条件：apiKey 为空");
        assertDoesNotThrow(properties::validateModel,
                "无 key 模式下合法模型必须放行，不得因校验 apiKey 而启动失败");
    }
}
