package cn.refinex.meridian.controlplane.secret.infra;

import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.common.json.JsonMappers;
import cn.refinex.meridian.controlplane.secret.app.KeyEncryptionService;
import cn.refinex.meridian.controlplane.secret.domain.SecretErrorCode;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

/**
 * HashiCorp Vault Transit 的密钥加密服务（ADR-0045 第 2 条）：主密钥只在 Vault 中，数据密钥经 {@code POST /v1/{mount}/encrypt/{key}}
 * 包装、{@code POST /v1/{mount}/decrypt/{key}} 解包，平台只保存 Transit 返回的 {@code vault:v…} 密文。Transit 中的密钥轮换后旧密文
 * 仍可解包。
 *
 * <p>令牌认证（{@code X-Vault-Token}，可选 {@code X-Vault-Namespace}）；令牌只来自配置（环境变量或 Secret），不进日志。直接用 Spring
 * {@link RestClient}，不引入 Spring Vault（只需要两个接口）。线程安全：不可变。
 */
public final class VaultTransitKeyEncryption implements KeyEncryptionService {

    /** 类型。 */
    public static final String TYPE = "vault";

    private final RestClient client;
    private final String mount;
    private final String keyName;

    /**
     * 创建服务。
     *
     * @param address Vault 地址，例如 {@code https://vault.internal:8200}；必填
     * @param token Vault 令牌；必填
     * @param namespace Vault 企业版的命名空间；可为空
     * @param mount Transit 的挂载点；必填
     * @param keyName Transit 中的密钥名；必填
     * @param timeout 连接与读取超时；必填
     */
    public VaultTransitKeyEncryption(
            String address, String token, String namespace, String mount, String keyName, Duration timeout) {
        Objects.requireNonNull(address, "meridian.control-plane.secrets.vault.address 必填");
        Objects.requireNonNull(token, "meridian.control-plane.secrets.vault.token 必填");
        this.mount = Objects.requireNonNull(mount, "mount");
        this.keyName = Objects.requireNonNull(keyName, "keyName");
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        RestClient.Builder builder =
                RestClient.builder().baseUrl(address).requestFactory(factory).defaultHeader("X-Vault-Token", token);
        if (namespace != null && !namespace.isBlank()) {
            builder.defaultHeader("X-Vault-Namespace", namespace);
        }
        this.client = builder.build();
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String keyId() {
        return "vault:" + mount + "/" + keyName;
    }

    @Override
    public String wrap(String tenantId, byte[] dataKey) {
        JsonNode data = call("encrypt", Map.of("plaintext", Base64.getEncoder().encodeToString(dataKey)));
        String ciphertext = data.path("ciphertext").asString(null);
        if (ciphertext == null) {
            throw unavailable("Vault Transit 的加密响应缺少 ciphertext", null);
        }
        return ciphertext;
    }

    @Override
    public byte[] unwrap(String tenantId, String wrapped) {
        JsonNode data = call("decrypt", Map.of("ciphertext", wrapped));
        String plaintext = data.path("plaintext").asString(null);
        if (plaintext == null) {
            throw unavailable("Vault Transit 的解密响应缺少 plaintext", null);
        }
        return Base64.getDecoder().decode(plaintext);
    }

    private JsonNode call(String operation, Map<String, String> body) {
        String response;
        try {
            response = client.post()
                    .uri("/v1/{mount}/{operation}/{key}", mount, operation, keyName)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            throw unavailable("Vault Transit 的 " + operation + " 调用失败", e);
        }
        if (response == null) {
            throw unavailable("Vault Transit 的 " + operation + " 响应为空", null);
        }
        return JsonMappers.lenient().readTree(response).path("data");
    }

    private static MeridianException unavailable(String detail, Throwable cause) {
        return new MeridianException(SecretErrorCode.ENCRYPTION_UNAVAILABLE, detail, cause);
    }
}
