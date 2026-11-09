package cn.refinex.meridian.controlplane.apikey.domain;

import java.security.SecureRandom;

/**
 * 新生成的 API Key 明文（ADR-0042）：{@code mk_live_} 加 40 位 base62，约 238 位熵。明文只在创建响应中出现一次，平台只保存它的
 * SHA-256 与随机部分的前 8 位。
 *
 * @param plaintext 明文
 */
public record ApiKeySecret(String plaintext) {

    /** 明文的固定前缀：{@code mk} 是平台简称，{@code live} 表示正式 Key。 */
    public static final String PREFIX = "mk_live_";

    /** 随机部分的长度。 */
    static final int RANDOM_LENGTH = 40;

    /** 保存与展示的随机部分长度：架构设计「核心表」规定只存前 8 位。 */
    static final int VISIBLE_LENGTH = 8;

    private static final char[] BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    /**
     * 生成一个新的明文。
     *
     * @param random 密码学安全的随机源
     * @return 明文
     */
    public static ApiKeySecret generate(SecureRandom random) {
        StringBuilder builder = new StringBuilder(PREFIX.length() + RANDOM_LENGTH).append(PREFIX);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            builder.append(BASE62[random.nextInt(BASE62.length)]);
        }
        return new ApiKeySecret(builder.toString());
    }

    /**
     * 随机部分的前 8 位，存入 {@code api_keys.key_prefix}。
     *
     * @return 8 个字符
     */
    public String visiblePrefix() {
        return plaintext.substring(PREFIX.length(), PREFIX.length() + VISIBLE_LENGTH);
    }

    @Override
    public String toString() {
        // 明文不出现在日志与异常信息中
        return "ApiKeySecret[" + PREFIX + visiblePrefix() + "…]";
    }
}
