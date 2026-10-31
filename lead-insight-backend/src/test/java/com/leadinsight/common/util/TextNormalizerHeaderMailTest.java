package com.leadinsight.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 邮件头在顶部的导出邮件（.eml / Outlook 转发）指纹回归防线。
 *
 * <p><b>缺陷背景：</b>{@code TextNormalizer.cleanLines} 原 实现 遇
 * {@code QUOTE_HISTORY_START}（含 {@code 发件人[:：].*}）/ {@code SIGNATURE_START} 即 {@code break}，
 * 而这类邮件的**首行**就是「发件人：…」→ 整封被抹空 → 指纹塌缩成空串的 SHA-256
 * （{@code e3b0c442…b855}），两封完全不同的邮件被判 40901 内容重复——**静默丢数据**。</p>
 *
 * <p><b>修复后两条铁律：</b>
 * ① 截断标记只在「已有正文存活」后生效，正文之前命中按普通邮件头跳过该行继续扫描；
 * ② 兜底不变式——**两段不同原文永不产生相同指纹**：归一化结果为空时绝不哈希空串，
 * 改为只用 1/2/4/5 步处理原文再哈希。</p>
 */
class TextNormalizerHeaderMailTest {

    /** 空串的 SHA-256（e3b0c442…b855）——任何真实邮件的指纹都不得等于它 */
    private static final String EMPTY_SHA256 = FingerprintUtil.sha256Hex("");

    @Test
    @DisplayName("两封不同但都以「发件人：」开头的邮件 → 指纹必须不同（修复前同塌缩为空串哈希）")
    void headerFirstDifferentMailsGetDifferentFingerprints() {
        String mailA = "发件人：张三 <zhangsan@example.com>\n主题：询价\n\n"
                + "您好，我们想采购服务器，预算 50 万。";
        String mailB = "发件人：李四 <lisi@example.com>\n主题：询价\n\n"
                + "您好，我们想采购笔记本电脑，预算 30 万。";

        String fpA = FingerprintUtil.fingerprint(mailA);
        String fpB = FingerprintUtil.fingerprint(mailB);

        assertNotEquals(fpA, fpB, "两封不同邮件绝不能同指纹，否则第二封被 40901 静默丢弃");
    }

    @Test
    @DisplayName("正文 + 底部「----原始邮件----」仍应截断只剩正文（不回退既有去重行为）")
    void bottomQuoteHistoryStillTruncated() {
        String body = "您好，我们想采购一批服务器，期待合作。";
        String withBottomQuote = body + "\n----原始邮件----\n发件人：王五\n> 旧内容";

        assertEquals(FingerprintUtil.fingerprint(body), FingerprintUtil.fingerprint(withBottomQuote),
                "正文之后的引用历史仍必须截断（既有行为不得回退）");
    }

    @Test
    @DisplayName("兜底不变式：指纹绝不等于空串哈希（全引用行邮件也必须给出非空指纹）")
    void fingerprintNeverEqualsEmptyStringHash() {
        String mailA = "发件人：张三\n您好，我们想采购服务器。";
        String fpA = FingerprintUtil.fingerprint(mailA);
        assertNotEquals(EMPTY_SHA256, fpA, "头部在顶部的邮件指纹不得等于空串哈希");

        // 行级清洗后必然为空的输入（全引用行）→ 兜底必须仍给出非空且可区分的指纹
        String allQuotedA = "> 旧内容A：我们想采购服务器";
        String allQuotedB = "> 旧内容B：我们想采购笔记本电脑";
        String fpQuotedA = FingerprintUtil.fingerprint(allQuotedA);
        String fpQuotedB = FingerprintUtil.fingerprint(allQuotedB);

        assertNotEquals(EMPTY_SHA256, fpQuotedA, "全引用行邮件也不得哈希空串");
        assertNotEquals(fpQuotedA, fpQuotedB, "两段不同原文永不产生相同指纹");
        assertNotEquals(fpA, fpQuotedA);
    }

    @Test
    @DisplayName("头部邮件的正文必须存活在归一化文本中（不只指纹不同）")
    void bodySurvivesWhenHeaderOnTop() {
        String mail = "发件人：张三\n您好，我们想采购服务器，预算 50 万。";

        String normalized = TextNormalizer.normalize(mail);

        assertTrue(normalized.contains("服务器"),
                "正文必须存活，实际归一化结果：" + normalized);
    }
}
