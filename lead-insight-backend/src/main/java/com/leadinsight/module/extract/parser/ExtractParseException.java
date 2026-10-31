package com.leadinsight.module.extract.parser;

/**
 * 抽取结果解析/校验失败（亮点1：触发重试，总尝试 3 次后置 FAILED 转人工）。
 * message 将截断落 {@code lead_extract_result.error_message}。
 */
public class ExtractParseException extends RuntimeException {

    public ExtractParseException(String message) {
        super(message);
    }

    public ExtractParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
