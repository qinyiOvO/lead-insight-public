package com.leadinsight.module.extract.service;

import com.leadinsight.module.extract.client.LlmChatResponse;
import com.leadinsight.module.extract.entity.ModelCallLog;
import com.leadinsight.module.extract.mapper.ModelCallLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 模型调用日志写入（亮点4 可观测性）。
 *
 * <p>每次调用（含每次重试）与被拒事件各落一行。<b>观测写失败绝不能打断主流程</b>：
 * 任何异常只记 warn，不影响线索创建。日志内容不含 key 明文（请求侧只落 SHA-256 指纹）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelCallLogger {

    public static final String SCENE_EXTRACT = "EXTRACT";
    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_RATE_LIMITED = "RATE_LIMITED";
    public static final String RESULT_PARSE_FAIL = "PARSE_FAIL";

    private final ModelCallLogMapper modelCallLogMapper;

    @SuppressWarnings("java:S107")
    public void record(Long leadId, Long importTaskId, String requestFingerprint, String callResult,
                       Integer httpStatus, LlmChatResponse response, long latencyMs, int retryNo,
                       boolean degraded, String errorCode, String errorMessage) {
        try {
            ModelCallLog row = new ModelCallLog();
            row.setLeadId(leadId);
            row.setImportTaskId(importTaskId);
            row.setScene(SCENE_EXTRACT);
            row.setModelName(response == null ? null : response.modelName());
            row.setRequestFingerprint(requestFingerprint);
            row.setHttpStatus(httpStatus);
            row.setCallResult(callResult);
            if (response != null) {
                row.setPromptTokens(response.promptTokens());
                row.setCompletionTokens(response.completionTokens());
                row.setTotalTokens(response.totalTokens());
            }
            row.setLatencyMs((int) Math.min(latencyMs, Integer.MAX_VALUE));
            row.setRetryNo(retryNo);
            row.setDegraded(degraded ? 1 : 0);
            row.setErrorCode(errorCode);
            row.setErrorMessage(truncate(errorMessage));
            modelCallLogMapper.insert(row);
        } catch (Exception e) {
            log.warn("model_call_log 写入失败（不影响主流程）: {}", e.getMessage());
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
