package cn.refinex.meridian.security.servicejwt;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 开发期静态服务凭据的配置（{@code meridian.security.dev-service-credentials}），只在 {@code dev} 与 {@code test} profile 中允许启用；
 * 在其他 profile 中启用会让应用启动失败（{@link ServiceCredentialAutoConfiguration}）。
 *
 * @param enabled 是否启用 {@link DevServiceCredentialResolver}；默认 false
 * @param credentials 凭据与服务名；可为空列表
 */
@ConfigurationProperties("meridian.security.dev-service-credentials")
public record DevServiceCredentialProperties(boolean enabled, List<Credential> credentials) {

    /** 规整空列表。 */
    public DevServiceCredentialProperties {
        credentials = credentials == null ? List.of() : List.copyOf(credentials);
    }

    /**
     * 一个开发用的服务凭据。明文只能来自环境变量或本地配置，不能提交到仓库（编码规范「配置」）。
     *
     * @param credential 凭据明文；必填
     * @param service 服务名；必填
     */
    public record Credential(String credential, String service) {}
}
