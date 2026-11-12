package cn.refinex.meridian.controlplane.secret.infra;

import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.controlplane.secret.app.KeyEncryptionService;
import cn.refinex.meridian.controlplane.secret.domain.SecretCipher;
import cn.refinex.meridian.controlplane.secret.domain.SecretErrorCode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

/**
 * 开发与测试用的密钥加密服务（ADR-0045 第 2 条）：主密钥是配置给出的本地文件中的 AES-256 密钥（base64），文件不存在时生成并以 0600
 * 写入（{@code CREATE_NEW}，两个进程同时启动也只生成一次）。数据密钥用 AES-256-GCM 包装，附加认证数据带租户 ID。
 *
 * <p>文件不能提交；生产用 Vault（{@link VaultTransitKeyEncryption}）。线程安全：不可变。
 */
public final class LocalMasterKeyEncryption implements KeyEncryptionService {

    /** 类型。 */
    public static final String TYPE = "local";

    private final byte[] masterKey;
    private final String keyId;
    private final SecureRandom random = new SecureRandom();

    /**
     * 读取主密钥文件，不存在时生成。
     *
     * @param file 主密钥文件
     * @throws UncheckedIOException 读写失败时
     * @throws IllegalStateException 文件内容不是 32 字节的 base64 时
     */
    public LocalMasterKeyEncryption(Path file) {
        this.masterKey = loadOrCreate(file, random);
        this.keyId = fingerprint(masterKey);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String keyId() {
        return keyId;
    }

    @Override
    public String wrap(String tenantId, byte[] dataKey) {
        return Base64.getEncoder().encodeToString(SecretCipher.encrypt(masterKey, dataKey, aad(tenantId), random));
    }

    @Override
    public byte[] unwrap(String tenantId, String wrapped) {
        try {
            return SecretCipher.decrypt(masterKey, Base64.getDecoder().decode(wrapped), aad(tenantId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new MeridianException(SecretErrorCode.ENCRYPTION_UNAVAILABLE, "数据密钥无法用当前的本地主密钥解包（主密钥文件换过？）", e);
        }
    }

    private static byte[] aad(String tenantId) {
        return ("meridian:data-key:" + tenantId).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] loadOrCreate(Path file, SecureRandom random) {
        try {
            if (!Files.exists(file)) {
                Path parent = file.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                byte[] key = new byte[SecretCipher.KEY_BYTES];
                random.nextBytes(key);
                try {
                    Files.writeString(
                            file,
                            Base64.getEncoder().encodeToString(key),
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE);
                    restrict(file);
                } catch (FileAlreadyExistsException raced) {
                    // 另一个进程先写入了：读它的
                }
            }
            byte[] key = Base64.getDecoder().decode(Files.readString(file).strip());
            if (key.length != SecretCipher.KEY_BYTES) {
                throw new IllegalStateException("本地主密钥文件的内容必须是 32 字节的 base64：" + file);
            }
            return key;
        } catch (IOException e) {
            throw new UncheckedIOException("读取本地主密钥文件失败：" + file, e);
        }
    }

    private static void restrict(Path file) throws IOException {
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(file, Set.copyOf(PosixFilePermissions.fromString("rw-------")));
        }
    }

    private static String fingerprint(byte[] key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            return "local:" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
