package cn.refinex.meridian.controlplane.secret.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 凭据值的加解密（ADR-0045 第 2 条）：AES-256-GCM，12 字节随机 nonce，128 位标签，附加认证数据为租户、凭据 ID 与版本，所以密文搬到
 * 别的行、别的租户或旧版本号上都无法解密。密文格式 {@code nonce || 密文 || 标签}。
 *
 * <p>线程安全：无状态，每次调用新建 {@link Cipher}。
 */
public final class SecretCipher {

    /** 数据密钥长度：AES-256。 */
    public static final int KEY_BYTES = 32;

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private SecretCipher() {}

    /**
     * 加密。
     *
     * @param dataKey 数据密钥，32 字节
     * @param plaintext 明文
     * @param aad 附加认证数据
     * @param random 随机数源，生成 nonce
     * @return {@code nonce || 密文 || 标签}
     */
    public static byte[] encrypt(byte[] dataKey, byte[] plaintext, byte[] aad, SecureRandom random) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dataKey, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            byte[] sealed = cipher.doFinal(plaintext);
            byte[] result = new byte[NONCE_BYTES + sealed.length];
            System.arraycopy(nonce, 0, result, 0, NONCE_BYTES);
            System.arraycopy(sealed, 0, result, NONCE_BYTES, sealed.length);
            return result;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM 加密失败", e);
        }
    }

    /**
     * 解密并校验标签。
     *
     * @param dataKey 数据密钥，32 字节
     * @param ciphertext {@code nonce || 密文 || 标签}
     * @param aad 附加认证数据，必须与加密时相同
     * @return 明文
     * @throws IllegalStateException 密文被篡改、密钥或附加认证数据不对时
     */
    public static byte[] decrypt(byte[] dataKey, byte[] ciphertext, byte[] aad) {
        if (ciphertext.length <= NONCE_BYTES) {
            throw new IllegalStateException("密文长度不正确");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(dataKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOf(ciphertext, NONCE_BYTES)));
            cipher.updateAAD(aad);
            return cipher.doFinal(ciphertext, NONCE_BYTES, ciphertext.length - NONCE_BYTES);
        } catch (GeneralSecurityException e) {
            // 不带原因：GCM 的失败信息没有可用内容，也不能暗示是哪一部分不对
            throw new IllegalStateException("密文无法解密：密钥、附加认证数据或密文不匹配");
        }
    }

    /**
     * 凭据值的附加认证数据。
     *
     * @param tenantId 租户 ID
     * @param secretId 凭据 ID
     * @param version 版本
     * @return UTF-8 字节
     */
    public static byte[] secretAad(String tenantId, String secretId, int version) {
        return ("meridian:secret:" + tenantId + ":" + secretId + ":" + version).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Webhook 签名密钥的附加认证数据（ADR-0049）：与凭据用同一把租户数据密钥，前缀不同，密文不能互换。
     *
     * @param tenantId 租户 ID
     * @param webhookId Webhook ID
     * @return UTF-8 字节
     */
    public static byte[] webhookAad(String tenantId, String webhookId) {
        return ("meridian:webhook:" + tenantId + ":" + webhookId).getBytes(StandardCharsets.UTF_8);
    }
}
