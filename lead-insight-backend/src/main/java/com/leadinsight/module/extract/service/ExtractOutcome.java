package com.leadinsight.module.extract.service;

import com.leadinsight.module.lead.enums.ExtractStatus;

/**
 * 一次抽取的终态结果（T2-02/03）。
 *
 * <p>终态只有三种（与 schema 口径一致）：
 * SUCCESS=解析成功；FAILED=解析失败转人工；DEGRADED=模型不可用降级（无 key/超时/429/熔断）。
 * {@code message} 为可读文案，落 {@code lead_extract_result.error_message} 与日志。</p>
 */
public record ExtractOutcome(ExtractStatus status, String message) {
}
