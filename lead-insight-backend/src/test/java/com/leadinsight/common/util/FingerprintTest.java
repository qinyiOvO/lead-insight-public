package com.leadinsight.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 内容指纹归一化 6 个边界用例（亮点3，ARCHITECTURE-v1.1 §5.3 R-4 的 QA 要求）。
 *
 * <p>第 6 例为本轮裁定新增：验证"归一化步序重排"（把「连续空白折叠」从原第 2 步下移到
 * 行级清洗之后）后，同一封邮件仅**软换行位置不同**也能判为同指纹；同时也说明为何必须重排
 * ——若折叠先于行级清洗，换行会被提前吃掉，用例 3 / 5 的行首锚定同样会失效。</p>
 *
 * <p>纯单元测试，不依赖 Spring 容器与数据库。</p>
 */
class FingerprintTest {

    private static void report(String name, String fpA, String fpB, boolean same) {
        System.out.println(String.format("[%s] %s%n    A=%s%n    B=%s",
                same ? "同指纹" : "不同指纹", name, fpA, fpB));
    }

    @Test
    @DisplayName("用例1：仅空白差异（首尾空白 / 空行 / CRLF+CR / 制表符 / 全角空格 / 不间断空格）→ 同指纹")
    void case1WhitespaceOnly() {
        String plain = "您好，我们想采购服务器";
        // 覆盖 CRLF、裸 CR、制表符、全角空格 U+3000、不间断空格 U+00A0 与多余空行的混合
        String padded = "\r\n \u3000您好，我们想采购服务器\u00A0 \t\r\r\n\n \u3000";
        String fpA = FingerprintUtil.fingerprint(plain);
        String fpB = FingerprintUtil.fingerprint(padded);
        report("用例1 仅空白差异", fpA, fpB, true);
        assertEquals(fpA, fpB);
    }

    @Test
    @DisplayName("用例2：仅大小写差异 → 同指纹")
    void case2CaseOnly() {
        String upper = "We Need a QUOTE for Servers";
        String lower = "we need a quote for servers";
        String fpA = FingerprintUtil.fingerprint(upper);
        String fpB = FingerprintUtil.fingerprint(lower);
        report("用例2 仅大小写差异", fpA, fpB, true);
        assertEquals(fpA, fpB);
    }

    @Test
    @DisplayName("用例3：签名块不同、正文相同 → 同指纹")
    void case3DifferentSignature() {
        String body = "您好，我们计划采购一套ERP系统，预算80万。";
        String withChineseSignature = body + "\n此致\n张三\n电话13800138000";
        String withEnglishSignature = body + "\nBest regards\nJohn\nTel 13900000000";
        String fpA = FingerprintUtil.fingerprint(withChineseSignature);
        String fpB = FingerprintUtil.fingerprint(withEnglishSignature);
        report("用例3 签名不同正文相同", fpA, fpB, true);
        assertEquals(fpA, fpB);
    }

    @Test
    @DisplayName("用例4：正文仅差一个数字（预算 50 万 vs 45 万）→ 必须不同指纹")
    void case4OneDigitDifferent() {
        String fifty = "您好，我们计划采购一批服务器，预算 50 万，请尽快报价。";
        String fortyFive = "您好，我们计划采购一批服务器，预算 45 万，请尽快报价。";
        String fpA = FingerprintUtil.fingerprint(fifty);
        String fpB = FingerprintUtil.fingerprint(fortyFive);
        report("用例4 正文数字不同", fpA, fpB, false);
        assertNotEquals(fpA, fpB, "正文数字不同必须产生不同指纹，否则会误判重复");
    }

    @Test
    @DisplayName("用例5：带一行 > 引用的回复邮件 → 与原文同指纹")
    void case5QuotedReply() {
        String original = "您好，我们想采购一批服务器，期待合作。";
        String reply = original + "\n> " + original;
        String fpA = FingerprintUtil.fingerprint(original);
        String fpB = FingerprintUtil.fingerprint(reply);
        report("用例5 带引用行的回复", fpA, fpB, true);
        assertEquals(fpA, fpB);
    }

    @Test
    @DisplayName("用例6：同一邮件仅软换行位置不同（句中换行处原为一个空格）→ 同指纹")
    void case6SoftWrapPositionInvariant() {
        // 同一封邮件的两种换行版本：换行点均落在原文的空格处（邮件客户端软换行/重新折行）
        String versionA = "客户要求尽快提供报价 并确认交付时间\n同时说明验收标准 与付款方式";
        String versionB = "客户要求尽快提供报价\n并确认交付时间 同时说明验收标准\n与付款方式";
        String fpA = FingerprintUtil.fingerprint(versionA);
        String fpB = FingerprintUtil.fingerprint(versionB);
        report("用例6 软换行位置不同", fpA, fpB, true);
        assertEquals(fpA, fpB,
                "同一邮件仅换行位置不同必须同指纹（重排后行级清洗先做、空白折叠最后做）");
    }

    @Test
    @DisplayName("附加：引用历史起始标记（原始邮件分割线 / On ... wrote:）之后的内容应被剔除")
    void extraQuoteHistoryMarkers() {
        String body = "您好，我们想采购一批服务器。";
        String withDivider = body + "\n-----原始邮件-----\n发件人：张三\n> 旧内容";
        String withOnWrote = body + "\nOn 2026-09-01 10:00, Li wrote:\n> 旧内容";
        String fpBody = FingerprintUtil.fingerprint(body);
        assertEquals(fpBody, FingerprintUtil.fingerprint(withDivider));
        assertEquals(fpBody, FingerprintUtil.fingerprint(withOnWrote));
        System.out.println("附加：引用历史标记剔除验证通过");
    }

    @Test
    @DisplayName("用例7（AC-19①补强）：仅在正文中间插入单个空白（全角空格 / 逗号后 1 空格）→ 同指纹")
    void case7InternalSingleWhitespace() {
        String plain = "我方计划采购一套CRM系统，预算50万，希望下季度上线。";
        String withFullWidthSpace = plain.replace("采购", "采购\u3000");
        String withCommaSpace = plain.replace("，", "， ");
        String fpA = FingerprintUtil.fingerprint(plain);
        assertEquals(fpA, FingerprintUtil.fingerprint(withFullWidthSpace),
                "插入单个全角空格不得改变指纹（AC-19①：仅空白差异→同指纹）");
        assertEquals(fpA, FingerprintUtil.fingerprint(withCommaSpace),
                "逗号后插入单个空格不得改变指纹（AC-19①）");
    }

    @Test
    @DisplayName("用例8（AC-19③补强）：无关键词签名块不同、正文相同 → 同指纹")
    void case8KeywordlessSignature() {
        String body = "我方计划采购一套CRM系统，预算50万，希望下季度上线。";
        String sigA = body + "\n市场部 张经理\n电话 13800138000";
        String sigB = body + "\n技术部 李工\n电话 13900139000";
        String fpA = FingerprintUtil.fingerprint(sigA);
        String fpB = FingerprintUtil.fingerprint(sigB);
        report("用例8 无关键词签名不同", fpA, fpB, true);
        assertEquals(fpA, fpB, "签名块（部门+姓名 / 电话行）不同、正文相同必须同指纹（AC-19③）");
    }

    @Test
    @DisplayName("用例9（AC-19⑤补强）：带完整邮件头（发件人/收件人）+ 引用的回复 → 与原文同指纹")
    void case9QuotedReplyWithFullHeader() {
        String body = "尊敬的销售团队：\n我方计划采购 Industrial Sensor 200 套，预算 50 万元，希望 2026 年 12 月前完成交付。";
        String reply = "-----原始邮件-----\n发件人: someone@example.com\n收件人: sales@example.com\n\n"
                + body + "\n\n> 以上为原始内容，请知悉";
        String fpA = FingerprintUtil.fingerprint(body);
        String fpB = FingerprintUtil.fingerprint(reply);
        report("用例9 完整邮件头引用回复", fpA, fpB, true);
        assertEquals(fpA, fpB, "Outlook 风格引用头（含收件人行）不得改变指纹（AC-19⑤）");
    }

    @Test
    @DisplayName("附加：联系信息行剥离不得吃掉公司名（ADR-008 铁律）")
    void extraCompanyNameSurvivesContactStrip() {
        String text = "我方计划采购一套系统。\n市场部 张经理\n电话 13800138000\n深圳市示例科技有限公司";
        String normalized = TextNormalizer.normalize(text);
        System.out.println("归一化后（联系信息行剥离）: " + normalized);
        org.junit.jupiter.api.Assertions.assertTrue(normalized.contains("深圳市示例科技有限公司"),
                "公司名是业务关键信息，必须留在存活文本中");
        org.junit.jupiter.api.Assertions.assertFalse(normalized.contains("张经理"), "联系人行应被剥离");
        org.junit.jupiter.api.Assertions.assertFalse(normalized.contains("13800138000"), "电话行应被剥离");
    }

    @Test
    @DisplayName("附加：正文中的业务关键信息（客户名/公司名/预算/时间）必须留在存活文本中")
    void extraBusinessFieldsSurvive() {
        String text = "客户张三，公司为鼎新科技有限公司，预算 88 万，期望 2026-11-30 前完成招标。";
        String normalized = TextNormalizer.normalize(text);
        System.out.println("归一生效文本: " + normalized);
        org.junit.jupiter.api.Assertions.assertTrue(normalized.contains("张三"));
        org.junit.jupiter.api.Assertions.assertTrue(normalized.contains("鼎新科技有限公司"));
        org.junit.jupiter.api.Assertions.assertTrue(normalized.contains("88"));
        org.junit.jupiter.api.Assertions.assertTrue(normalized.contains("2026-11-30"));
    }
}
