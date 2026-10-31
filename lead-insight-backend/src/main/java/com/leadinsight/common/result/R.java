package com.leadinsight.common.result;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应包装。结构固定为 {code,message,data}，code=0 表示成功。
 */
@Data
@NoArgsConstructor
public class R<T> {

    /** 0=成功，非 0=错误码（见 ResultCode） */
    private int code;

    /** 人类可读描述 */
    private String message;

    /** 业务数据，可为 null */
    private T data;

    public R(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> R<T> ok() {
        return new R<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    public static <T> R<T> ok(String message, T data) {
        return new R<>(ResultCode.SUCCESS.getCode(), message, data);
    }

    public static <T> R<T> fail(ResultCode resultCode) {
        return new R<>(resultCode.getCode(), resultCode.getMessage(), null);
    }

    public static <T> R<T> fail(ResultCode resultCode, String message) {
        return new R<>(resultCode.getCode(), message, null);
    }

    public static <T> R<T> fail(int code, String message, T data) {
        return new R<>(code, message, data);
    }
}
