package com.leadinsight.common.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 邮件正文文本归一化（亮点3 内容指纹幂等去重）。
 *
 * <p>步序（v1.3，2026-10-31 DEF-06 修复裁定；在 v1.2 重排基础上修订第 3/5 步）：</p>
 * <ol>
 *   <li>换行统一：CRLF / CR → LF</li>
 *   <li>英文全部转小写（{@link Locale#ROOT}，规避土耳其语 i 问题）</li>
 *   <li>行级清洗（此时仍保留行结构）：逐行 trim；删除以 {@code >} 开头的行；
 *       删除「联系信息行」（纯电话行 / 纯邮箱行 / 部门+姓名短行，保守启发式，见 {@link #isContactLine}）；
 *       从匹配原始邮件分割线 / {@code On ... wrote:} / {@code 发件人[:：]} / {@code 收件人[:：]}
 *       起至文末全部删除；从匹配签名标记（此致 / 顺祝商祺 / 祝好 / Best regards / Regards / Sent from my /
 *       本邮件…保密 / Confidentiality Notice）起至文末全部删除
 *       —— 因第 2 步已转小写，本步正则必须大小写不敏感；
 *       <b>截断只在已有正文存活后生效</b>，正文之前命中的标记按普通邮件头跳过（防整封抹空）</li>
 *   <li>全角空格 U+3000 与不间断空格 U+00A0 → 半角空格</li>
 *   <li><b>移除全部空白</b>（含换行，覆盖 Unicode 空白类 \p{Z}）——AC-19①「仅空白差异→同指纹」的
 *       operational 定义要求：插入/删除任意单个空白都必须不改变指纹。v1.2 的「折叠为单个空格」仍保留
 *       一个空格，插入一个空白即变指纹，已被 DEF-06 用例①证伪（「，」→「， 」判为不同）</li>
 *   <li>对剩余文本取 SHA-256，小写十六进制（见 {@link FingerprintUtil}）</li>
 * </ol>
 *
 * <p><b>铁律：第 3 步只剔除「引用历史与签名」，绝不剔正文</b>；客户名称 / 公司名 / 预算 / 时间
 * 等业务关键信息必须留在存活文本中——联系信息行剥离以「短行 + 无句末标点 + 不含公司后缀」为
 * 前置条件（见 {@link #isContactLine}），公司名片行永远存活。归一化后文本不落库，仅用于计算指纹，
 * 避免引入第二份可能失真的正文。</p>
 */
public final class TextNormalizer {

    /** 第 3 步正则统一标志：第 2 步已转小写，故必须大小写不敏感 */
    private static final int MARKER_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /** 引用行：整行以 ">" 开头（可多个） */
    private static final Pattern QUOTED_LINE = Pattern.compile(">+.*");

    /** 引用历史起始标记（整行匹配，行首锚定由「逐行处理」天然保证） */
    private static final Pattern QUOTE_HISTORY_START = Pattern.compile(
            "-{2,}\\s*原始邮件.*|on .* wrote:.*|(?:发件人|收件人)[:：].*", MARKER_FLAGS);

    /** 签名块起始标记（整行匹配） */
    private static final Pattern SIGNATURE_START = Pattern.compile(
            "(此致|顺祝商祺|祝好|best regards|regards|sent from my|本邮件.*保密|confidentiality notice).*",
            MARKER_FLAGS);

    /** 联系信息行（Conservative-③）：纯电话/手机行（可带「电话/手机/联系方式/Tel/mobile」前缀） */
    private static final Pattern PHONE_LINE = Pattern.compile(
            "(?:电话|手机|联系方式|tel|mobile)[:：]?\\s*1[3-9]\\d{9}|1[3-9]\\d{9}", MARKER_FLAGS);

    /** 联系信息行：纯邮箱行 */
    private static final Pattern EMAIL_LINE = Pattern.compile(
            "[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+", MARKER_FLAGS);

    /** 联系信息行：部门/团队 + 姓名（如「市场部 张经理」「技术部 李工」） */
    private static final Pattern DEPT_NAME_LINE = Pattern.compile(
            "[\\u4e00-\\u9fa5]{2,10}(?:部|组|科|室|中心|团队)\\s*[\\u4e00-\\u9fa5]{2,5}", MARKER_FLAGS);

    /** 句末标点：命中即视为正文行，不参与联系信息行剥离 */
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？；]");

    /** 公司/企业后缀：公司名是业务关键信息（ADR-008 铁律），所在行禁止剥离 */
    private static final Pattern COMPANY_SUFFIX = Pattern.compile("(公司|集团|厂|店|企业|事务所)");

    /** 联系信息行长度上限：只剥「短行」，防止误吃正文 */
    private static final int CONTACT_LINE_MAX_LENGTH = 20;

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

        // 步骤 3：行级清洗（引用行删除 + 联系信息行删除 + 引用历史/签名截断）
        text = cleanLines(text);

        String normalized = removeWhitespace(text);
        if (!normalized.isEmpty()) {
            return normalized;
        }

        // 兜底：跳过第 3 步，只用 1/2/4/5 步处理原文（绝不哈希空串）
        String fallback = raw.replace("\r\n", "\n").replace('\r', '\n').toLowerCase(Locale.ROOT);
        return removeWhitespace(fallback);
    }

    /** 步骤 4 + 5：全角/不间断空格转半角，随后移除全部空白（含换行） */
    private static String removeWhitespace(String text) {
        String spaced = text.replace('\u3000', ' ').replace('\u00A0', ' ');
        return ANY_WHITESPACE.matcher(spaced).replaceAll("");
    }

    /**
     * 第 3 步行级清洗：逐行 trim、丢弃空行与引用行/联系信息行，遇到引用历史或签名标记即停止（截断到文末）。
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
            if (isContactLine(trimmed)) {
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

    /**
     * 联系信息行判定（Conservative-③，DEF-06 裁定）：只剥「短行 + 无句末标点 + 不含公司后缀」，
     * 且整行必须完全匹配电话 / 邮箱 / 部门+姓名之一，正文与公司名片行一律不剥。
     */
    private static boolean isContactLine(String line) {
        if (line.length() > CONTACT_LINE_MAX_LENGTH) {
            return false;
        }
        if (SENTENCE_END.matcher(line).find()) {
            return false;
        }
        if (COMPANY_SUFFIX.matcher(line).find()) {
            return false;
        }
        return PHONE_LINE.matcher(line).matches()
                || EMAIL_LINE.matcher(line).matches()
                || DEPT_NAME_LINE.matcher(line).matches();
    }
}
