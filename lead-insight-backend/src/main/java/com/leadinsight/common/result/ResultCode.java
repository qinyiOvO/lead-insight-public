package com.leadinsight.common.result;

import lombok.Getter;

/**
 * 统一错误码表，严格对应 ARCHITECTURE-v1.1 §5.4。
 */
@Getter
public enum ResultCode {

    SUCCESS(0, 200, "成功"),
    PARAM_ERROR(40001, 400, "参数校验失败"),
    UNAUTHORIZED(40100, 401, "未认证或令牌无效"),
    TOKEN_EXPIRED(40101, 401, "令牌已过期"),
    FORBIDDEN(40300, 403, "无权限执行该操作"),
    NOT_FOUND(40400, 404, "资源不存在"),
    DUPLICATE_CONTENT(40901, 409, "内容重复"),
    ILLEGAL_STATUS_TRANSITION(42201, 422, "非法状态迁移"),
    RATE_LIMITED(42900, 429, "请求过于频繁"),
    INTERNAL_ERROR(50000, 500, "系统内部错误"),
    LLM_UNAVAILABLE(50301, 503, "模型服务不可用");

    /** 业务错误码 */
    private final int code;

    /** 建议 HTTP 状态码 */
    private final int httpStatus;

    /** 默认提示文案 */
    private final String message;

    ResultCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }
}
