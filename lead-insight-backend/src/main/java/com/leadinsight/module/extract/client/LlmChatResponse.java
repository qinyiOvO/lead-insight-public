package com.leadinsight.module.extract.client;

/**
 * 一次对话补全的返回。token 用量落 {@code model_call_log} 供论文量化。
 */
public record LlmChatResponse(
        String content,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        String modelName) {
}
