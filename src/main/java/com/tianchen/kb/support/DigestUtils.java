package com.tianchen.kb.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 稳定的 SHA-256 摘要工具：用于生成文档 ID、切片 ID 与幂等去重键。 */
public final class DigestUtils {

    private DigestUtils() {
    }

    public static String sha256(String input) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** 生成短标识（16 位十六进制），用于对外暴露的切片引用号。 */
    public static String shortId(String input) {
        return sha256(input).substring(0, 16);
    }
}
