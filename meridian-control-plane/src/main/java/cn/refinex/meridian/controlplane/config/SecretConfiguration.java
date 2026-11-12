package cn.refinex.meridian.controlplane.config;

import cn.refinex.meridian.common.net.EndpointPolicy;
import cn.refinex.meridian.controlplane.secret.app.KeyEncryptionService;
import cn.refinex.meridian.controlplane.secret.infra.LocalMasterKeyEncryption;
import cn.refinex.meridian.controlplane.secret.infra.VaultTransitKeyEncryption;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 凭据库与模型目录的装配（ADR-0045）：按 {@code meridian.control-plane.secrets.key-encryption} 选密钥加密服务，按
 * {@code provider-endpoints} 构造端点策略。
 */
@Configuration(proxyBeanMethods = false)
public class SecretConfiguration {

    /**
     * 密钥加密服务。
     *
     * @param properties 配置
     * @return {@code local} 或 {@code vault} 的实现
     * @throws IllegalStateException 没有配置或取值不认识时（启动失败，不默认选一个）
     */
    @Bean
    public KeyEncryptionService keyEncryptionService(ControlPlaneProperties properties) {
        ControlPlaneProperties.Secrets secrets = properties.secrets();
        String type = secrets.keyEncryption();
        if (LocalMasterKeyEncryption.TYPE.equals(type)) {
            String file = secrets.local().masterKeyFile();
            if (file == null || file.isBlank()) {
                throw new IllegalStateException("meridian.control-plane.secrets.local.master-key-file 必填");
            }
            return new LocalMasterKeyEncryption(Path.of(file));
        }
        if (VaultTransitKeyEncryption.TYPE.equals(type)) {
            ControlPlaneProperties.Vault vault = secrets.vault();
            return new VaultTransitKeyEncryption(
                    vault.address(), vault.token(), vault.namespace(), vault.mount(), vault.keyName(), vault.timeout());
        }
        throw new IllegalStateException(
                "meridian.control-plane.secrets.key-encryption 必须是 local 或 vault（生产用 vault），当前为 " + type);
    }

    /**
     * 供应商与搜索后端端点的地址策略。
     *
     * @param properties 配置
     * @return 策略
     */
    @Bean
    public EndpointPolicy endpointPolicy(ControlPlaneProperties properties) {
        return properties.providerEndpoints().policy();
    }
}
