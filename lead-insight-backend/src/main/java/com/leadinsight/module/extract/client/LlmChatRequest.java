package com.leadinsight.module.extract.client;

/**
 * 一次对话补全请求（与具体厂商解耦的最小参数集）。
 *
 * @param prompt    完整提示词（模板已渲染，含 "json" 字样与格式示例）
 * @param maxTokens 输出 token 上限，防止 JSON 被截断
 */
public record LlmChatRequest(String prompt, int maxTokens) {
}
