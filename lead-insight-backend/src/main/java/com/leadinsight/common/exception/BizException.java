package com.leadinsight.common.exception;

import com.leadinsight.common.result.ResultCode;
import lombok.Getter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务异常。携带业务错误码与建议 HTTP 状态，由 GlobalExceptionHandler 统一转成 R。
 */
@Getter
public class BizException extends RuntimeException {

    private final ResultCode resultCode;

    /** 字段级错误（键=字段名，值=可读提示），仅参数校验类异常使用，可为空 */
    private final Map<String, String> fieldErrors;

    public BizException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.resultCode = resultCode;
        this.fieldErrors = null;
    }

    public BizException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
        this.fieldErrors = null;
    }

    public BizException(ResultCode resultCode, String message, Map<String, String> fieldErrors) {
        super(message);
        this.resultCode = resultCode;
        this.fieldErrors = fieldErrors == null ? null : new LinkedHashMap<>(fieldErrors);
    }

    public static BizException of(ResultCode resultCode) {
        return new BizException(resultCode);
    }

    /** 拼装字段级参数校验异常：message 汇总各字段提示，data 返回字段映射 */
    public static BizException fieldErrors(Map<String, String> errors) {
        String message = errors.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .reduce((a, b) -> a + "; " + b)
                .orElse(ResultCode.PARAM_ERROR.getMessage());
        return new BizException(ResultCode.PARAM_ERROR, message, errors);
    }
}
