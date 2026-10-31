package com.leadinsight.module.extract.prompt;

import com.leadinsight.common.util.TextUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 提示模板加载与渲染（T2-02，ADR-004 决策 1：模板外置 {@code prompt/extract-v2.txt}，
 * 不许硬编码在 Java 字符串里，可迭代而不改代码；v1 保留作历史版本供论文/测试报告追溯）。
 *
 * <p>占位符整体替换；模板自身不做任何转义，转义由 HTTP 层的 JSON 序列化完成。</p>
 *
 * <p><b>v2 变更（2026-10-02，team-lead 裁定）：</b>新增 {@link #TODAY_PLACEHOLDER}——渲染时注入
 * 当前日期（{@link LocalDate}，yyyy-MM-dd），并把第 5 条规则改为「相对时间基于当前日期换算为
 * 绝对日期」。依据：验收规格（R2 走查判据表 行 1「可解析为日期且距今 ≤7 天」）与
 * ARCHITECTURE §5.2 紧迫度规则都要求 {@code expectedTime} 是日期；v1 把相对时间置 null
 * 会使紧迫度 20 分系统性作废（40 条金标准样本 38 条为相对表述）。同时守住防幻觉红线：
 * 原文完全无时间信息时必须为 null，严禁推测。</p>
 */
@Component
public class PromptLoader {

    public static final String PLACEHOLDER = "{{ORIGINAL_TEXT}}";

    /** 当前日期占位符：供模型把「7天内/本周五前」等相对时间换算成绝对日期（expectedTime） */
    public static final String TODAY_PLACEHOLDER = "{{today}}";

    /** 与 ADR-004 / lead_extract_result.prompt_version 对齐 */
    public static final String PROMPT_VERSION = "v2";

    private final String template;
    private final Clock clock;

    /**
     * Spring 装配构造。
     *
     * <p><b>必须标 {@code @Autowired}：</b>本类有两个构造器（本 public 构造 + 下方包级 Clock 构造）。
     * Spring 仅在「类中恰好只有 1 个构造器」时才自动选用；有多个且都不标注时会退回要求无参构造器，
     * 进而抛 {@code NoSuchMethodException: <init>()} 导致应用启动即崩（InMemoryLlmQuotaGuard 前车之鉴）。
     * 故显式标注，指明由 Spring 用这个。</p>
     */
    @Autowired
    public PromptLoader() {
        this(Clock.systemDefaultZone());
    }

    /** 可注入时钟的构造（单测用固定 Clock，断言渲染结果确定、不依赖真实系统时间） */
    PromptLoader(Clock clock) {
        this.clock = clock;
        this.template = load("prompt/extract-v2.txt");
    }

    /** 渲染抽取提示词：注入当前日期（yyyy-MM-dd）与原文 */
    public String renderExtractPrompt(String originalText) {
        String text = originalText == null ? "" : originalText;
        String today = LocalDate.now(clock).format(DateTimeFormatter.ISO_LOCAL_DATE);
        return template.replace(TODAY_PLACEHOLDER, today).replace(PLACEHOLDER, text);
    }

    private static String load(String location) {
        try (InputStream in = new ClassPathResource(location).getInputStream()) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (TextUtils.isBlank(content) || !content.contains(PLACEHOLDER)) {
                throw new IllegalStateException("提示模板缺失占位符 " + PLACEHOLDER + ": " + location);
            }
            if (!content.contains(TODAY_PLACEHOLDER)) {
                // 缺当前日期占位符 = 相对时间无法换算 → expectedTime 恒 null → 紧迫度 20 分系统性作废，
                // 属静默失效，必须在启动期拦截而不是等到线上才发现
                throw new IllegalStateException("提示模板缺失占位符 " + TODAY_PLACEHOLDER + ": " + location);
            }
            return content;
        } catch (IOException e) {
            throw new IllegalStateException("提示模板加载失败: " + location, e);
        }
    }
}
