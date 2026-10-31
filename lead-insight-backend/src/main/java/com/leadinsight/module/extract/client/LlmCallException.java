package com.leadinsight.module.extract.client;

/**
 * 模型调用失败（可分类，驱动限流退避 / 熔断计数 / DEGRADED 终态判定）。
 *
 * <p>铁律：异常消息只含 HTTP 状态、错误摘要与分类，<b>绝不携带 API Key</b>
 * （key 只存在于请求头，组装请求与记录日志时均不得拼接进 message）。</p>
 */
public class LlmCallException extends RuntimeException {

    /** 调用结果分类，与 model_call_log.call_result 对齐 */
    public enum FailureKind {
        /** 远端 429 或本地令牌不足 */
        RATE_LIMITED,
        /** 连接/读取超时 */
        TIMEOUT,
        /** 其他 HTTP/网络错误 */
        ERROR
    }

    private final FailureKind kind;
    private final Integer httpStatus;

    public LlmCallException(FailureKind kind, Integer httpStatus, String message) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    public FailureKind getKind() {
        return kind;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }
}
