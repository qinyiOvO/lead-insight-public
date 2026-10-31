package com.leadinsight.common.util;

/**
 * 纯文本工具（无业务、无副作用）。
 */
public final class TextUtils {

    private TextUtils() {
    }

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static boolean isNotBlank(String value) {
        return !isBlank(value);
    }

    /** 去除首尾空白；结果为空串时返回 null（便于「留空即不写库」） */
    public static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 安全截断，避免超出数据库列长度 */
    public static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
