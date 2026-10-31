package com.leadinsight.module.importtask.service.impl;

import com.leadinsight.common.exception.BizException;
import com.leadinsight.common.result.ResultCode;
import com.leadinsight.module.importtask.enums.ImportTaskStatus;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批量导入纯规则单测：空白段过滤（批复②）、数量校验、K-1 终态判定（批复③④）。
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

    // ---------------------- K-1 终态判定 ----------------------

    @Test
    void finalStatus_zeroFailIsSuccess_evenAllDuplicate() {
        // 边界口径：全去重命中 → fail=0 → SUCCESS（K-1 字面：FAILED 仅限全部转人工/降级/异常）
        assertEquals(ImportTaskStatus.SUCCESS, ImportTaskRules.finalStatus(0, 5, 0));
        assertEquals(ImportTaskStatus.SUCCESS, ImportTaskRules.finalStatus(3, 0, 0));
    }

    @Test
    void finalStatus_allManualWorkloadIsFailed() {
        // 全部条目转人工/降级/创建异常，无一自动成功
        assertEquals(ImportTaskStatus.FAILED, ImportTaskRules.finalStatus(0, 0, 4));
    }

    @Test
    void finalStatus_mixedIsPartial() {
        assertEquals(ImportTaskStatus.PARTIAL, ImportTaskRules.finalStatus(2, 1, 1));
        assertEquals(ImportTaskStatus.PARTIAL, ImportTaskRules.finalStatus(0, 3, 1));
        assertEquals(ImportTaskStatus.PARTIAL, ImportTaskRules.finalStatus(1, 0, 2));
    }
}
