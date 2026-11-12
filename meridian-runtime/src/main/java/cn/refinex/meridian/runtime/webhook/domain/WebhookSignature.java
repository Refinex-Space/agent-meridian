package cn.refinex.meridian.runtime.webhook.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook 的签名（架构设计「Webhook」，ADR-0049）：{@code Meridian-Signature: t={ts},v1={hex}}，{@code v1} 是以签名密钥对
 * {@code "{ts}.{请求体}"} 计算的 HMAC-SHA256 的小写十六进制，{@code ts} 是 Unix 秒。接收方按同样的方式计算并比较，再按时间戳拒绝过旧的
 * 请求（防重放）。
 */
public final class WebhookSignature {

    /** 签名头名。 */
    public static final String HEADER = "Meridian-Signature";

    private static final String ALGORITHM = "HmacSHA256";

    private WebhookSignature() {}

    /**
     * 计算签名头的取值。
     *
     * @param secret 签名密钥
     * @param timestamp Unix 秒
     * @param body 请求体原文
     * @return {@code t={ts},v1={hex}}
     */
    public static String header(String secret, long timestamp, String body) {
        return "t=" + timestamp + ",v1=" + hmac(secret, timestamp + "." + body);
    }

    /**
     * 以密钥计算 HMAC-SHA256 的十六进制。
     *
     * @param secret 密钥
     * @param message 消息
     * @return 小写十六进制
     */
    public static String hmac(String secret, String message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JDK 不支持 HmacSHA256", e);
        }
    }
}
