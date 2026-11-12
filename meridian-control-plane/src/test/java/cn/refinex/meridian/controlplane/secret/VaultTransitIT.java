package cn.refinex.meridian.controlplane.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.controlplane.secret.app.KeyEncryptionService;
import cn.refinex.meridian.controlplane.secret.domain.SecretErrorCode;
import cn.refinex.meridian.controlplane.secret.infra.VaultTransitKeyEncryption;
import cn.refinex.meridian.controlplane.support.AdminClient;
import cn.refinex.meridian.controlplane.support.AdminClient.Reply;
import cn.refinex.meridian.controlplane.support.ControlPlaneTestBase;
import cn.refinex.meridian.controlplane.support.TenantFixture;
import cn.refinex.meridian.testsupport.TestDatabase;
import java.security.SecureRandom;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.vault.VaultContainer;

/**
 * Vault Transit 的密钥加密服务（T15a-A02，ADR-0045 第 2 条）：Testcontainers 的 Vault（开发模式，启用 Transit 并建好密钥），整个应用以
 * {@code key-encryption=vault} 运行：数据密钥经 Transit 包装，凭据的写入、解析、轮换都可用；Transit 的密钥轮换后旧包装仍能解开；令牌
 * 不对时以 {@code secret.encryption_unavailable} 失败。
 */
class VaultTransitIT extends ControlPlaneTestBase {

    /** Vault 镜像，固定版本。 */
    static final String VAULT_IMAGE = "hashicorp/vault:1.20.4";

    /** 开发模式的根令牌，只用于测试容器。 */
    static final String ROOT_TOKEN = "test-root-token";

    static final VaultContainer<?> VAULT = new VaultContainer<>(VAULT_IMAGE)
            .withVaultToken(ROOT_TOKEN)
            .withInitCommand("secrets enable transit", "write -f transit/keys/meridian-secrets");

    static {
        VAULT.start();
    }

    @DynamicPropertySource
    static void vaultProperties(DynamicPropertyRegistry registry) {
        registry.add("meridian.control-plane.secrets.key-encryption", () -> "vault");
        registry.add("meridian.control-plane.secrets.vault.address", VAULT::getHttpHostAddress);
        registry.add("meridian.control-plane.secrets.vault.token", () -> ROOT_TOKEN);
    }

    @Autowired
    KeyEncryptionService encryption;

    @Test
    void secretsAreEnvelopeEncryptedWithVaultTransit() throws Exception {
        AdminClient client = client();
        TenantFixture fixture = TenantFixture.create(client);
        String tenantId = fixture.get("tenant");

        assertThat(encryption).isInstanceOf(VaultTransitKeyEncryption.class);
        assertThat(encryption.keyId()).isEqualTo("vault:transit/meridian-secrets");
        String wrapped = JdbcClient.create(TestDatabase.migratorDataSource())
                .sql("SELECT wrapped_key FROM cp.secret_data_keys WHERE tenant_id = ? AND key_encryption = 'vault'")
                .param(tenantId)
                .query(String.class)
                .single();
        assertThat(wrapped).startsWith("vault:v1:");
        assertThat(resolve(fixture, fixture.get("secret")).text("value")).isEqualTo("fixture-secret-value");

        // Transit 的密钥轮换后：旧租户的包装仍能解开，新租户的数据密钥用新版本包装
        assertThat(VAULT.execInContainer(
                                "/bin/sh",
                                "-c",
                                "VAULT_ADDR=http://127.0.0.1:8200 VAULT_TOKEN=" + ROOT_TOKEN
                                        + " vault write -f transit/keys/meridian-secrets/rotate")
                        .getExitCode())
                .isZero();
        assertThat(encryption.unwrap(tenantId, wrapped)).hasSize(32);
        TenantFixture later = TenantFixture.create(client);
        assertThat(JdbcClient.create(TestDatabase.migratorDataSource())
                        .sql("SELECT wrapped_key FROM cp.secret_data_keys WHERE tenant_id = ?")
                        .param(later.get("tenant"))
                        .query(String.class)
                        .single())
                .startsWith("vault:v2:");
        Reply rotated = client.admin(
                "POST",
                "/admin/v1/tenants/" + tenantId + "/secrets/" + fixture.get("secret") + ":rotate",
                token("owner"),
                "{\"value\":\"rotated-under-vault\"}");
        assertThat(rotated.status()).isEqualTo(200);
        assertThat(resolve(fixture, fixture.get("secret")).text("value")).isEqualTo("rotated-under-vault");
        assertThat(resolve(later, later.get("secret")).text("value")).isEqualTo("fixture-secret-value");
    }

    @Test
    void wrongTokenOrUnreachableVaultIsUnavailable() {
        byte[] dataKey = new byte[32];
        new SecureRandom().nextBytes(dataKey);
        VaultTransitKeyEncryption wrongToken = new VaultTransitKeyEncryption(
                VAULT.getHttpHostAddress(), "wrong-token", null, "transit", "meridian-secrets", Duration.ofSeconds(5));
        VaultTransitKeyEncryption unreachable = new VaultTransitKeyEncryption(
                "http://127.0.0.1:1", ROOT_TOKEN, null, "transit", "meridian-secrets", Duration.ofSeconds(2));

        assertThatThrownBy(() -> wrongToken.wrap("ten_x", dataKey))
                .isInstanceOf(MeridianException.class)
                .satisfies(e -> assertThat(((MeridianException) e).errorCode())
                        .isEqualTo(SecretErrorCode.ENCRYPTION_UNAVAILABLE))
                .hasMessageNotContaining("wrong-token");
        assertThatThrownBy(() -> unreachable.unwrap("ten_x", "vault:v1:abc")).isInstanceOf(MeridianException.class);
        assertThat(encryption.unwrap("ten_x", encryption.wrap("ten_x", dataKey)))
                .isEqualTo(dataKey);
    }

    private Reply resolve(TenantFixture fixture, String secretId) {
        return client().internal(
                        "POST",
                        "/internal/v1/secrets:resolve",
                        serviceToken(),
                        "{\"tenant_id\":\"" + fixture.get("tenant") + "\",\"secret_id\":\"" + secretId + "\"}");
    }
}
