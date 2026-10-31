package com.leadinsight.module.importtask.service.impl;

import com.leadinsight.common.exception.BizException;
import com.leadinsight.common.result.ResultCode;
import com.leadinsight.module.importtask.enums.ImportTaskStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量导入纯规则集（批复②③ + K-1 终态口径），无副作用、可独立单测。
 * 被 ImportTaskExecutor（executor 包）跨包引用，类与跨包方法须 public。
 */
public final class ImportTaskRules {

    /** 单次批量导入的业务上限（按空白段过滤后的条数校验，批复②） */
    public static final int MAX_SEGMENTS = 200;

    private ImportTaskRules() {
    }

    /**
     * 空白段过滤：去除空白与符号后不含任何汉字/字母/数字的段视为空段——丢弃、
     * 不计入 total、不建明细（纯符号裁定：纯标点/表情/装饰符零信息量，只会产生垃圾线索，
     * 与走查判据 v1.3 fixture「空白/纯符号段不计入」对齐）。
     * 有效段返回 trim 后文本（指纹/抽取链路有各自归一化，此处 trim 仅做存储降噪）。
     *
     * @return 过滤后的有效文本段（保持提交顺序）
     */
    public static List<String> filterSegments(List<String> raw) {
        List<String> segments = new ArrayList<>();
        if (raw == null) {
            return segments;
        }
        for (String text : raw) {
            if (text != null && !text.trim().isEmpty() && hasContent(text)) {
                segments.add(text.trim());
            }
        }
        return segments;
    }

    /**
     * 判断文本是否含实质内容：存在任一汉字/字母/数字码点即有效
     * （Character.isLetterOrDigit 覆盖 CJK 汉字、全角字母数字；标点/符号/emoji 均为否）。
     */
    static boolean hasContent(String text) {
        return text.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    /**
     * 数量校验：全空白 → 40001；过滤后超过 200 → 40001（上限按过滤后数）。
     */
    public static void validateSegmentCount(int validCount) {
        if (validCount == 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "提交内容均为空白段，没有可导入的邮件文本");
        }
        if (validCount > MAX_SEGMENTS) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "单次最多导入 " + MAX_SEGMENTS + " 条，当前有效条数 " + validCount);
        }
    }

    /**
     * 任务终态判定（DEF-04 四桶口径，替代原 K-1 三桶；2026-10-31 总监核定分支序）：
     * failCount==0 且 manualCount==0 → SUCCESS（含全部去重命中的边界：去重不算失败；
     * 全零任务按旧代码防御行为仍为 SUCCESS）；
     * successCount==0 且 duplicateCount==0 且 manualCount==0 → FAILED（全部条目创建异常，无一产线索）；
     * 其余（有转人工 manualCount>0 / 有失败且部分成功 / 混合）→ PARTIAL。
     * 转人工的线索其实已入库，标 FAILED 会误导用户以为一条没导进来（K-1 Amendment：DEF-04）。
     */
    public static ImportTaskStatus finalStatus(int successCount, int duplicateCount,
                                               int manualCount, int failCount) {
        if (failCount == 0 && manualCount == 0) {
            return ImportTaskStatus.SUCCESS;
        }
        if (successCount == 0 && duplicateCount == 0 && manualCount == 0) {
            return ImportTaskStatus.FAILED;
        }
        return ImportTaskStatus.PARTIAL;
    }
}
