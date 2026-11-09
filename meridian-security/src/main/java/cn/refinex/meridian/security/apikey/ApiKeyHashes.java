package cn.refinex.meridian.security.apikey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * API Key 与其他 Bearer 令牌的哈希：SHA-256 的小写十六进制（架构设计「认证」：只存 SHA-256 哈希）。control-plane 存储、内部校验
 * 接口与各服务的缓存都用它，保证同一个 Key 在各处得到相同的值。
 */
public final class ApiKeyHashes {

    private ApiKeyHashes() {}

    /**
     * 计算哈希。
     *
     * @param plaintext 明文
     * @return 64 位小写十六进制
     */
    public static String sha256(String plaintext) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 规定必须提供的算法
            throw new IllegalStateException(e);
        }
    }
}
