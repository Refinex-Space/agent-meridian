package cn.refinex.meridian.controlplane.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.ErrorCodes;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.controlplane.secret.domain.SecretCipher;
import cn.refinex.meridian.controlplane.secret.domain.SecretErrorCode;
import cn.refinex.meridian.controlplane.secret.infra.LocalMasterKeyEncryption;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 凭据的加解密与本地主密钥（T15a-A01 的单元部分，ADR-0045 第 2 条）。 */
class SecretCryptoTest {

    private final SecureRandom random = new SecureRandom();

    @Test
    void ciphertextIsBoundToTenantSecretAndVersion() {
        byte[] key = key();
        byte[] plaintext = "sk-live-0123456789".getBytes(StandardCharsets.UTF_8);
        byte[] aad = SecretCipher.secretAad("ten_a", "sec_1", 1);

        byte[] sealed = SecretCipher.encrypt(key, plaintext, aad, random);

        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("sk-live");
        assertThat(SecretCipher.decrypt(key, sealed, aad)).isEqualTo(plaintext);
        // 同一明文两次加密得到不同的密文（随机 nonce）
        assertThat(SecretCipher.encrypt(key, plaintext, aad, random)).isNotEqualTo(sealed);
        // 换租户、换凭据、换版本、换密钥、改一个字节，都解不开
        assertThatThrownBy(() -> SecretCipher.decrypt(key, sealed, SecretCipher.secretAad("ten_b", "sec_1", 1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SecretCipher.decrypt(key, sealed, SecretCipher.secretAad("ten_a", "sec_2", 1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SecretCipher.decrypt(key, sealed, SecretCipher.secretAad("ten_a", "sec_1", 2)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SecretCipher.decrypt(key(), sealed, aad)).isInstanceOf(IllegalStateException.class);
        byte[] tampered = sealed.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThatThrownBy(() -> SecretCipher.decrypt(key, tampered, aad)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void localMasterKeyIsGeneratedOnceWithOwnerOnlyPermissions(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("keys/master.key");
        LocalMasterKeyEncryption first = new LocalMasterKeyEncryption(file);
        byte[] dataKey = key();

        String wrapped = first.wrap("ten_a", dataKey);
        LocalMasterKeyEncryption reloaded = new LocalMasterKeyEncryption(file);

        assertThat(Files.readString(file).strip()).hasSize(44);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                .isEqualTo("rw-------");
        assertThat(reloaded.keyId()).isEqualTo(first.keyId()).startsWith("local:");
        assertThat(reloaded.unwrap("ten_a", wrapped)).isEqualTo(dataKey);
        // 包装绑定租户：换一个租户解包失败
        assertThatThrownBy(() -> reloaded.unwrap("ten_b", wrapped))
                .isInstanceOf(MeridianException.class)
                .satisfies(e -> assertThat(((MeridianException) e).errorCode())
                        .isEqualTo(SecretErrorCode.ENCRYPTION_UNAVAILABLE));
        // 换了主密钥文件，旧的包装解不开
        LocalMasterKeyEncryption other = new LocalMasterKeyEncryption(dir.resolve("other.key"));
        assertThat(other.keyId()).isNotEqualTo(first.keyId());
        assertThatThrownBy(() -> other.unwrap("ten_a", wrapped)).isInstanceOf(MeridianException.class);
    }

    @Test
    void malformedMasterKeyFileFailsFast(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("bad.key");
        Files.writeString(file, "c2hvcnQ=");

        assertThatThrownBy(() -> new LocalMasterKeyEncryption(file)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void errorCodesAreWellFormed() {
        ErrorCodes.verify(SecretErrorCode.values());
    }

    private byte[] key() {
        byte[] key = new byte[SecretCipher.KEY_BYTES];
        random.nextBytes(key);
        return key;
    }
}
