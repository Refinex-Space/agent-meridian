package cn.refinex.meridian.controlplane.config;

import cn.refinex.meridian.security.servicejwt.ServiceJwts;
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
 */
@ConfigurationProperties("meridian.control-plane")
public record ControlPlaneProperties(
        @DefaultValue Tenants tenants,
        @DefaultValue ApiKeys apiKeys,
        List<String> platformAdmins,
        List<String> platformAdminGroups,
        @DefaultValue ServiceTokens serviceTokens) {

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
}
