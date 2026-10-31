package com.leadinsight.module.importtask.service.impl;

import com.leadinsight.common.exception.BizException;
import com.leadinsight.common.result.ResultCode;
import com.leadinsight.module.importtask.enums.ImportTaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量导入纯规则单测：空白段过滤（批复②）、数量校验、DEF-04 四桶终态判定（success/duplicate/manual/fail）。
 */
class ImportTaskRulesTest {

    // ---------------------- 空白段过滤 ----------------------

    @Test
    void filterSegments_dropsNullAndBlankSegments() {
        List<String> result = ImportTaskRules.filterSegments(
                Arrays.asList("  邮件正文  ", null, "", "   ", "\t\n", "第二封"));
        assertEquals(List.of("邮件正文", "第二封"), result);
    }

    @Test
    void filterSegments_keepsSubmissionOrder() {
        List<String> result = ImportTaskRules.filterSegments(List.of("c", "a", "b"));
        assertEquals(List.of("c", "a", "b"), result);
    }

    @Test
    void filterSegments_nullInputYieldsEmptyList() {
        assertTrue(ImportTaskRules.filterSegments(null).isEmpty());
    }

    // ---------------------- 纯符号段过滤（走查裁定） ----------------------

    @Test
    void filterSegments_pureFullWidthSymbolsDropped() {
        // 全角装饰符号（～～～）零信息量，视为空段
        List<String> result = ImportTaskRules.filterSegments(List.of("～～～", "真实内容"));
        assertEquals(List.of("真实内容"), result);
    }

    @Test
    void filterSegments_pureEmojiAndAsciiSymbolsDropped() {
        // emoji 装饰 + ASCII 标点/下划线装饰，均无汉字/字母/数字 → 丢弃
        List<String> result = ImportTaskRules.filterSegments(
                Arrays.asList("*** ### !!!", "🎉🎉🎉", "___---___", "邮件正文有内容"));
        assertEquals(List.of("邮件正文有内容"), result);
    }

    @Test
    void filterSegments_mixedWhitespaceWithSymbolsDropped() {
        // 混合空白 + 符号（含全角空格），归一后无实质字符 → 丢弃
        List<String> result = ImportTaskRules.filterSegments(
                Arrays.asList(" \t ~~~ * \u3000 ~ \n", "第二封有效邮件"));
        assertEquals(List.of("第二封有效邮件"), result);
    }

    @Test
    void filterSegments_singleDigitOrLetterKept() {
        // 含任一数字/字母/汉字即有效（"123"、英文单词、单个汉字都保留）
        List<String> result = ImportTaskRules.filterSegments(Arrays.asList("123", "OK", "您好"));
        assertEquals(List.of("123", "OK", "您好"), result);
    }

    // ---------------------- 数量校验 ----------------------

    @Test
    void validateSegmentCount_allBlankThrows40001() {
        BizException e = assertThrows(BizException.class, () -> ImportTaskRules.validateSegmentCount(0));
        assertEquals(ResultCode.PARAM_ERROR.getCode(), e.getResultCode().getCode());
    }

    @Test
    void validateSegmentCount_over200Throws40001() {
        BizException e = assertThrows(BizException.class, () -> ImportTaskRules.validateSegmentCount(201));
        assertEquals(ResultCode.PARAM_ERROR.getCode(), e.getResultCode().getCode());
    }

    @Test
    void validateSegmentCount_exactly200Passes() {
        ImportTaskRules.validateSegmentCount(200);
        ImportTaskRules.validateSegmentCount(1);
    }

    // ---------------------- DEF-04 四桶终态判定（success/duplicate/manual/fail） ----------------------

    @Test
    @DisplayName("DEF-04：无转人工且无失败 → SUCCESS（含全部去重命中的边界）")
    void finalStatus_zeroManualZeroFailIsSuccess() {
        // 去重不算失败；全去重命中同样是 SUCCESS
        assertEquals(ImportTaskStatus.SUCCESS, ImportTaskRules.finalStatus(0, 5, 0, 0));
        assertEquals(ImportTaskStatus.SUCCESS, ImportTaskRules.finalStatus(3, 0, 0, 0));
        assertEquals(ImportTaskStatus.SUCCESS, ImportTaskRules.finalStatus(2, 1, 0, 0));
    }

    @Test
    @DisplayName("DEF-04：有转人工 → PARTIAL（全 DEGRADED 也不再是 FAILED——线索其实已入库）")
    void finalStatus_anyManualIsPartial() {
        // 证红锚点：旧口径 finalStatus(0,0,3)（三桶）= FAILED，把 3 条已入库的转人工线索报成整体失败
        assertEquals(ImportTaskStatus.PARTIAL, ImportTaskRules.finalStatus(0, 0, 3, 0));
        assertEquals(ImportTaskStatus.PARTIAL, ImportTaskRules.finalStatus(1, 0, 2, 0));
        assertEquals(ImportTaskStatus.PARTIAL, ImportTaskRules.finalStatus(0, 3, 2, 0));
    }

    @Test
    @DisplayName("DEF-04：全部创建失败（未产线索）→ FAILED")
    void finalStatus_allFailedNoLeadIsFailed() {
        assertEquals(ImportTaskStatus.FAILED, ImportTaskRules.finalStatus(0, 0, 0, 4));
    }

    @Test
    @DisplayName("DEF-04：混合（成功/去重 + 失败）→ PARTIAL")
    void finalStatus_mixedWithFailIsPartial() {
        assertEquals(ImportTaskStatus.PARTIAL, ImportTaskRules.finalStatus(2, 1, 0, 1));
        assertEquals(ImportTaskStatus.PARTIAL, ImportTaskRules.finalStatus(1, 0, 0, 2));
    }
}
