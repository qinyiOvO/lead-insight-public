package com.leadinsight.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORS 可配置化单测（走查整改：白名单原硬编码 5173，并行验证通道 5174~5178 全部 403）。
 * 直接构建 CorsConfiguration 校验放行/拦截，无需起 Spring 上下文。
 */
class WebMvcConfigCorsTest {

    @Test
    void defaultOrigins_matchContractDefaults() {
        // 默认值必须与用户主环境契约一致：仅 Vite 5173 两种写法（零行为变化）
        assertEquals(List.of("http://localhost:5173", "http://127.0.0.1:5173"),
                List.of(WebMvcConfig.DEFAULT_ALLOWED_ORIGINS.split(",")),
                "默认白名单改动会破坏主环境契约，禁止静默变更");
    }

    @Test
    void defaultConfig_allows5173_andRejectsOtherPorts() {
        WebMvcConfig config = new WebMvcConfig(List.of(
                "http://localhost:5173", "http://127.0.0.1:5173"));
        CorsConfiguration cors = config.buildCorsConfiguration();

        assertNotNull(cors.checkOrigin("http://localhost:5173"), "5173 应放行");
        assertNotNull(cors.checkOrigin("http://127.0.0.1:5173"), "127.0.0.1:5173 应放行");
        assertNull(cors.checkOrigin("http://localhost:5174"), "未列入白名单的 5174 应拦截（403 Invalid CORS request）");
        assertNull(cors.checkOrigin("http://evil.example.com"), "任意外域应拦截");
    }

    @Test
    void configuredExtraOrigin_isAllowedWithoutCodeChange() {
        // 并行验证通道：在 app.cors.allowed-origins 追加 5174~5178 即放行（本次整改目标）
        WebMvcConfig config = new WebMvcConfig(List.of(
                "http://localhost:5173", "http://127.0.0.1:5173",
                "http://localhost:5174", "http://localhost:5175",
                "http://localhost:5176", "http://localhost:5177", "http://localhost:5178"));
        CorsConfiguration cors = config.buildCorsConfiguration();

        for (int port = 5173; port <= 5178; port++) {
            assertNotNull(cors.checkOrigin("http://localhost:" + port),
                    "配置追加后 " + port + " 应放行");
        }
        assertNull(cors.checkOrigin("http://localhost:5179"), "名单外仍拦截");
    }

    @Test
    void corsRuleShape_methodsHeadersCredentialsMaxAge() {
        // 锚定既有规则形状，防止可配置化改造悄悄改变方法/头/凭证/预检缓存
        CorsConfiguration cors = new WebMvcConfig(List.of("http://localhost:5173"))
                .buildCorsConfiguration();
        assertTrue(cors.getAllowCredentials(), "允许携带凭证（前端带 Authorization）");
        assertEquals(86400L, cors.getMaxAge(), "预检缓存 24 小时");
        assertEquals(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"), cors.getAllowedMethods());
        assertEquals(List.of("Content-Type", "Authorization"), cors.getAllowedHeaders());
    }
}
