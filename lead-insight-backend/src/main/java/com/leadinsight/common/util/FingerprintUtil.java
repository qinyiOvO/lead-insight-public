package com.leadinsight.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 内容指纹工具（亮点3）：归一化文本 -> SHA-256 -> 小写十六进制（64 位字符）。
 */
public final class FingerprintUtil {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final String ALGORITHM = "SHA-256";

    private FingerprintUtil() {
    }

    /**
     * 对原始正文执行「6 步归一化 + SHA-256」，返回 64 位小写十六进制指纹。
     */
    public static String fingerprint(String rawText) {
        return sha256Hex(TextNormalizer.normalize(rawText));
    }

    /**
     * 对给定文本直接取 SHA-256 小写十六进制（不做归一化）。
     */
    public static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            byte[] bytes = digest.digest(text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8));
            return toHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 " + ALGORITHM, e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
