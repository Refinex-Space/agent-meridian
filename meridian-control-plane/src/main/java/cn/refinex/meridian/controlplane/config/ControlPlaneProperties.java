package cn.refinex.meridian.controlplane.config;

import cn.refinex.meridian.common.net.EndpointPolicy;
import cn.refinex.meridian.security.servicejwt.ServiceJwts;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * control-plane 的配置（{@code meridian.control-plane}，ADR-0042）。
 *
 * @param tenants 新租户的默认配额
 * @param apiKeys API Key 的默认值
 * @param platformAdmins 平台管理员的主体标识；可为空列表
 * @param platformAdminGroups 平台管理员所在的组（令牌中组声明的取值，ADR-0043）；可为空列表。两个列表都为空时没有人能创建租户
 * @param serviceTokens 服务 JWT 的签发（ADR-0044）
 * @param secrets 凭据库（ADR-0045）
 * @param providerEndpoints 供应商与搜索后端端点的地址策略（ADR-0045 第 5 条）
 */
@ConfigurationProperties("meridian.control-plane")
public record ControlPlaneProperties(
        @DefaultValue Tenants tenants,
        @DefaultValue ApiKeys apiKeys,
        List<String> platformAdmins,
        List<String> platformAdminGroups,
        @DefaultValue ServiceTokens serviceTokens,
        @DefaultValue Secrets secrets,
        @DefaultValue ProviderEndpoints providerEndpoints) {

    /** 规整空列表。 */
    public ControlPlaneProperties {
        platformAdmins = platformAdmins == null ? List.of() : List.copyOf(platformAdmins);
        platformAdminGroups = platformAdminGroups == null ? List.of() : List.copyOf(platformAdminGroups);
    }

    /**
     * 新租户的默认配额。
     *
     * @param defaultMaxConcurrentTurns 并发配额，默认 20（架构设计「认领」中的租户并发计数行，ADR-0027）
     * @param defaultEventRetentionDays 事件保留天数，默认 365（架构设计「数据保留」）
     */
    public record Tenants(
            @DefaultValue("20") int defaultMaxConcurrentTurns,
            @DefaultValue("365") int defaultEventRetentionDays) {}

    /**
     * API Key 的默认值。
     *
     * @param defaultRateLimitPerMinute 创建时不给出上限的默认值，默认 600（ADR-0042）
     */
    public record ApiKeys(@DefaultValue("600") int defaultRateLimitPerMinute) {}

    /**
     * 服务 JWT 的签发（架构设计「认证」中"服务之间"一行，ADR-0044）。
     *
     * @param issuer 签发方（{@code iss}），默认 {@value ServiceJwts#DEFAULT_ISSUER}
     * @param keyDirectory 签名密钥的目录（开发方式：没有密钥时生成并写入，Compose 中是命名卷）；必填。生产从密钥管理系统加载（T28）
     * @param clients 可以换取令牌的服务；可为空列表
     */
    public record ServiceTokens(
            @DefaultValue(ServiceJwts.DEFAULT_ISSUER) String issuer, String keyDirectory, List<Client> clients) {

        /** 规整空列表。 */
        public ServiceTokens {
            clients = clients == null ? List.of() : List.copyOf(clients);
        }
    }

    /**
     * 一个可以换取服务 JWT 的服务客户端。
     *
     * @param service 服务名（客户端 ID、令牌的 {@code sub}）；必填
     * @param secret 客户端密钥，只能来自环境变量或 Secret；必填。启动时只保留 SHA-256
     * @param audiences 允许换取的目标服务名；必填，非空
     */
    public record Client(String service, String secret, List<String> audiences) {

        /** 规整空列表。 */
        public Client {
            audiences = audiences == null ? List.of() : List.copyOf(audiences);
        }

        /**
         * 不打印密钥。
         *
         * @return 不含密钥的描述
         */
        @Override
        public String toString() {
            return "Client[service=" + service + ", audiences=" + audiences + "]";
        }
    }

    /**
     * 凭据库（架构设计「凭据」，ADR-0045 第 2 条）。
     *
     * @param keyEncryption 密钥加密服务：{@code local}（开发与测试）或 {@code vault}；没有默认值，部署必须显式选择
     * @param local 本地主密钥
     * @param vault Vault Transit
     * @param dataKeyCacheTtl 解包后的数据密钥在内存中的缓存时间，默认 5 分钟
     */
    public record Secrets(
            String keyEncryption,
            @DefaultValue Local local,
            @DefaultValue Vault vault,
            @DefaultValue("5m") Duration dataKeyCacheTtl) {}

    /**
     * 本地主密钥（只用于开发与测试）。
     *
     * @param masterKeyFile 主密钥文件的路径，不存在时生成；不提交
     */
    public record Local(String masterKeyFile) {}

    /**
     * HashiCorp Vault Transit。
     *
     * @param address Vault 地址
     * @param token 令牌，只能来自环境变量或 Secret
     * @param namespace 企业版命名空间；可为空
     * @param mount Transit 的挂载点，默认 {@code transit}
     * @param keyName Transit 中的密钥名，默认 {@code meridian-secrets}
     * @param timeout 连接与读取超时，默认 5 秒
     */
    public record Vault(
            String address,
            String token,
            String namespace,
            @DefaultValue("transit") String mount,
            @DefaultValue("meridian-secrets") String keyName,
            @DefaultValue("5s") Duration timeout) {

        /**
         * 不打印令牌。
         *
         * @return 不含令牌的描述
         */
        @Override
        public String toString() {
            return "Vault[address=" + address + ", namespace=" + namespace + ", mount=" + mount + ", keyName=" + keyName
                    + ", timeout=" + timeout + "]";
        }
    }

    /**
     * 供应商与搜索后端端点的地址策略（ADR-0045 第 5 条）。
     *
     * @param allowLoopback 是否放行回环地址：只在开发与测试为真（本机的脚本化模型服务）
     * @param privateNetworks 放行的私有网段（CIDR）；不配置时为全部私有网段，生产按私有化推理服务所在网段收窄
     */
    public record ProviderEndpoints(boolean allowLoopback, List<String> privateNetworks) {

        /**
         * 转为端点策略。
         *
         * @return 策略
         */
        public EndpointPolicy policy() {
            return new EndpointPolicy(
                    allowLoopback, privateNetworks == null ? EndpointPolicy.DEFAULT_PRIVATE_NETWORKS : privateNetworks);
        }
    }
}
