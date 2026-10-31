package com.leadinsight.module.extract.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提示词渲染单测（相对时间换算规格落地）。
 *
 * <p><b>为什么存在：</b>已冻结的验收规格（`docs/05-测试/R2-走查判据表.md` 行 1）要求
 * `expectedTime` 为「可解析为日期且距今 ≤7 天」，`ARCHITECTURE §5.2` 的紧迫度按"距今天数"分档。
 * 若提示词只把相对时间写进 `expectedTimeText`、而 `expectedTime` 恒 null，则这 20 分系统性作废
 * （40 条金标准样本 38 条为相对表述 → 一条 HIGH 都出不来）。故提示词必须注入「当前日期」
 * 并要求模型把相对时间换算成绝对日期。</p>
 *
 * <p><b>确定性：</b>用 {@link Clock#fixed} 固定「今天」为 2026-10-02（Asia/Shanghai），
 * 断言渲染结果即可确定，不依赖真实系统时间。</p>
 */
class PromptLoaderTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 固定时钟：2026-10-02（本机时区） */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(LocalDate.of(2026, 10, 2).atStartOfDay(ZONE).toInstant(), ZONE);

    private final PromptLoader loader = new PromptLoader(FIXED_CLOCK);

    @Test
    @DisplayName("渲染结果注入当前日期（固定 Clock → 确定性 2026-10-02）")
    void renderInjectsFixedToday() {
        String rendered = loader.renderExtractPrompt("希望 3 天内答复");
        assertTrue(rendered.contains("2026-10-02"),
                "提示词必须带上当前日期供模型换算相对时间，实际渲染：" + rendered);
    }

    @Test
    @DisplayName("渲染后不残留任何未替换占位符")
    void renderLeavesNoPlaceholder() {
        String rendered = loader.renderExtractPrompt("客户邮件正文");
        assertFalse(rendered.contains(PromptLoader.TODAY_PLACEHOLDER),
                "{{today}} 必须被替换为当前日期");
        assertFalse(rendered.contains(PromptLoader.PLACEHOLDER),
                "{{ORIGINAL_TEXT}} 必须被替换为原文");
    }

    @Test
    @DisplayName("原文被完整注入（转义交给 HTTP 层，模板不转义）")
    void renderInjectsOriginalText() {
        String original = "客户：广州振华五金制品有限公司，希望本周五前拿到正式报价";
        assertTrue(loader.renderExtractPrompt(original).contains(original),
                "原文必须原样进入提示词");
    }

    @Test
    @DisplayName("提示词版本升级为 v2（论文/测试报告可追溯）")
    void promptVersionIsV2() {
        assertEquals("v2", PromptLoader.PROMPT_VERSION, "本次规格变更后版本必须为 v2");
    }
}
