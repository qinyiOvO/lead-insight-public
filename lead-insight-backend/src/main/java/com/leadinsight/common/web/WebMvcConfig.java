package com.leadinsight.common.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Web MVC 配置。前后端分离：开发期前端走 Vite 代理（同源），
 * 此处仅对显式允许的开发来源放开，生产由 Nginx 同源部署或按域名白名单配置。
 *
 * <p>CORS 白名单可配置（走查整改）：{@code app.cors.allowed-origins}（逗号分隔）。
 * 默认值保持用户主环境行为零变化——仅 Vite 默认端口 5173 的两种写法；
 * 并行验证通道（5174~5178）需放行时在配置里追加，无需改代码。</p>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** 默认白名单（与 app.cors.allowed-origins 缺省值一致，主环境零变化） */
    static final String DEFAULT_ALLOWED_ORIGINS = "http://localhost:5173,http://127.0.0.1:5173";

    private final List<String> allowedOrigins;

    public WebMvcConfig(@Value("${app.cors.allowed-origins:" + DEFAULT_ALLOWED_ORIGINS + "}")
                        List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        CorsConfiguration config = buildCorsConfiguration();
        registry.addMapping("/api/**")
                .allowedOriginPatterns(config.getAllowedOriginPatterns().toArray(String[]::new))
                .allowedMethods(config.getAllowedMethods().toArray(String[]::new))
                .allowedHeaders(config.getAllowedHeaders().toArray(String[]::new))
                .allowCredentials(true)
                .maxAge(config.getMaxAge());
    }

    /**
     * 组装 CORS 规则（包可见：单测直接校验放行/拦截，无需起 Spring 上下文）。
     * allowedOriginPatterns + allowCredentials(true)：白名单按模式匹配，
     * 不在名单内的来源 Spring 会以 "Invalid CORS request" 403 拒绝。
     */
    CorsConfiguration buildCorsConfiguration() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "Authorization"));
        config.setAllowCredentials(true);
        config.setMaxAge(86400L);
        return config;
    }
}
