package cn.refinex.meridian.security.oidc;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 开发期控制台用户令牌的配置（{@code meridian.security.dev-console-users}），只在 {@code dev}、{@code test} profile 中允许启用；
 * 在其他 profile 中启用会让应用启动失败（{@link ConsoleAuthAutoConfiguration}）。T14b 起由 OIDC 取代。
 *
 * @param enabled 是否启用 {@link DevConsoleUserResolver}；默认 false
 * @param users 令牌与用户；可为空列表
 */
@ConfigurationProperties("meridian.security.dev-console-users")
public record DevConsoleUserProperties(boolean enabled, List<User> users) {

    /** 规整空列表。 */
    public DevConsoleUserProperties {
        users = users == null ? List.of() : List.copyOf(users);
    }

    /**
     * 一个开发用的控制台用户。令牌只能来自环境变量或本地配置，不能提交到仓库（编码规范「配置」）。
     *
     * @param token 令牌明文；必填
     * @param subject 主体标识；必填
     */
    public record User(String token, String subject) {}
}
