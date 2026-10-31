package com.leadinsight.common.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 邮件正文文本归一化（亮点3 内容指纹幂等去重）。
 *
 * <p>步序（team-lead R1 裁定修订版，共 6 步；原版把"全空白折叠"放在行级清洗之前，
 * 会同时吃掉换行，导致第 1 步的换行统一与第 3 步的行首锚定全部失效——现版将
 * 全空白折叠挪到行级清洗之后，保留行结构至清洗完成，再用空格抹平换行，
 * 使"同一封邮件仅在句中换行位置不同"也能判为同指纹）：</p>
 * <ol>
 *   <li>换行统一：CRLF / CR → LF</li>
 *   <li>英文全部转小写（{@link Locale#ROOT}，规避土耳其语 i 问题）</li>
 *   <li>行级清洗（此时仍保留行结构）：逐行 trim；删除以 {@code >} 开头的行；
 *       从匹配原始邮件分割线 / {@code On ... wrote:} / {@code 发件人[:：]} 起至文末全部删除；
 *       从匹配签名标记（此致 / 顺祝商祺 / 祝好 / Best regards / Regards / Sent from my /
 *       本邮件…保密 / Confidentiality Notice）起至文末全部删除
 *       —— 因第 2 步已转小写，本步正则必须大小写不敏感；
 *       <b>截断只在已有正文存活后生效</b>，正文之前命中的标记按普通邮件头跳过（防整封抹空）</li>
 *   <li>全角空格 U+3000 与不间断空格 U+00A0 → 半角空格</li>
 *   <li>全部连续空白（含换行，且覆盖 Unicode 空白类 \p{Z}）折叠为单个空格，首尾 trim</li>
 *   <li>对剩余文本取 SHA-256，小写十六进制（见 {@link FingerprintUtil}）</li>
 * </ol>
 *
 * <p><b>铁律：第 3 步只剔除「引用历史与签名」，绝不剔正文</b>；客户名称 / 公司名 / 预算 / 时间
 * 等业务关键信息必须留在存活文本中。归一化后文本不落库，仅用于计算指纹，
 * 避免引入第二份可能失真的正文。</p>
 */
public final class TextNormalizer {

    /** 第 3 步正则统一标志：第 2 步已转小写，故必须大小写不敏感 */
    private static final int MARKER_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /** 引用行：整行以 ">" 开头（可多个） */
    private static final Pattern QUOTED_LINE = Pattern.compile(">+.*");

    /** 引用历史起始标记（整行匹配，行首锚定由「逐行处理」天然保证） */
    private static final Pattern QUOTE_HISTORY_START = Pattern.compile(
            "-{2,}\\s*原始邮件.*|on .* wrote:.*|发件人[:：].*", MARKER_FLAGS);

    /** 签名块起始标记（整行匹配） */
    private static final Pattern SIGNATURE_START = Pattern.compile(
            "(此致|顺祝商祺|祝好|best regards|regards|sent from my|本邮件.*保密|confidentiality notice).*",
            MARKER_FLAGS);

    /** 全部空白：Java 的 \s 仅覆盖 ASCII，故补齐 Unicode 空白类 \p{Z}（含 U+3000 / U+00A0） */
    private static final Pattern ANY_WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");

    private TextNormalizer() {
    }

    /**
     * 按 6 步规则归一化。输入为 null 时按空串处理。
     *
     * <p><b>兜底不变式（两段不同原文永不产生相同指纹）：</b>行级清洗把全文清空
     * （典型：邮件头在顶部的导出邮件 / 全引用行邮件）时，绝不能哈希空串——空串的 SHA-256
     * 恒为 {@code e3b0c442…b855}，会让所有被清空的邮件互相撞车（第二封被 40901 误判重复、
     * 静默丢数据）。此时改为跳过第 3 步（不做引用/签名截断），只用 1/2/4/5 步处理原文再哈希。</p>
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }

        // 步骤 1：换行符统一 CRLF / CR -> LF
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');

        // 步骤 2：英文全部转小写
        text = text.toLowerCase(Locale.ROOT);

        // 步骤 3：行级清洗（引用行删除 + 引用历史/签名截断）
        text = cleanLines(text);

        String normalized = collapseWhitespace(text);
        if (!normalized.isEmpty()) {
            return normalized;
        }

        // 兜底：跳过第 3 步，只用 1/2/4/5 步处理原文（绝不哈希空串）
        String fallback = raw.replace("\r\n", "\n").replace('\r', '\n').toLowerCase(Locale.ROOT);
        return collapseWhitespace(fallback);
    }

    /** 步骤 4 + 5：全角/不间断空格转半角，连续空白（含换行）折叠为单空格并去首尾 */
    private static String collapseWhitespace(String text) {
        String spaced = text.replace('\u3000', ' ').replace('\u00A0', ' ');
        return ANY_WHITESPACE.matcher(spaced).replaceAll(" ").strip();
    }

    /**
     * 第 3 步行级清洗：逐行 trim、丢弃空行与引用行，遇到引用历史或签名标记即停止（截断到文末）。
     *
     * <p><b>截断生效前置条件（2026-10-02 复产修复）：</b>截断标记只在「已有正文存活」后才生效。
     * 邮件头在**顶部**的导出邮件（.eml / Outlook 转发）首行即「发件人：…」，若无条件 break
     * 会把整封抹空 → 指纹塌缩成空串哈希。故正文之前命中时按普通邮件头跳过该行继续扫描；
     * 正文之后命中仍截断到文末（既有去重行为不变）。</p>
     */
    private static String cleanLines(String text) {
        String[] lines = text.split("\n", -1);
        List<String> kept = new ArrayList<>(lines.length);
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (QUOTED_LINE.matcher(trimmed).matches()) {
                continue;
            }
            if (QUOTE_HISTORY_START.matcher(trimmed).matches()
                    || SIGNATURE_START.matcher(trimmed).matches()) {
                if (kept.isEmpty()) {
                    // 正文之前命中：是邮件头，不是"正文之后的引用历史"——跳过该行继续扫描
                    continue;
                }
                break;
            }
            kept.add(trimmed);
        }
        return String.join("\n", kept);
    }
}
