package cn.refinex.meridian.security.oidc;

import cn.refinex.meridian.security.apikey.ApiKeyHashes;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 开发期的 {@link ConsoleUserResolver}：令牌与主体来自配置 {@link DevConsoleUserProperties}，只在 {@code dev}、{@code test} profile
 * 生效（ADR-0042）。只保存令牌的 SHA-256，按哈希查找。线程安全：创建后不可变。
 */
public final class DevConsoleUserResolver implements ConsoleUserResolver {

    private final Map<String, ConsoleUser> byHash;

    /**
     * 按配置创建解析器。
     *
     * @param properties 开发期控制台用户配置
     * @throws IllegalStateException 有空的令牌或主体、或令牌重复时
     */
    public DevConsoleUserResolver(DevConsoleUserProperties properties) {
        Map<String, ConsoleUser> users = new HashMap<>();
        for (DevConsoleUserProperties.User user : properties.users()) {
            if (isBlank(user.token()) || isBlank(user.subject())) {
                throw new IllegalStateException("开发期控制台用户必须给出 token 与 subject：" + user.subject());
            }
            if (users.put(ApiKeyHashes.sha256(user.token()), new ConsoleUser(user.subject())) != null) {
                throw new IllegalStateException("开发期控制台用户的令牌重复：" + user.subject());
            }
        }
        this.byHash = Map.copyOf(users);
    }

    @Override
    public Optional<ConsoleUser> resolve(String token) {
        return Optional.ofNullable(byHash.get(ApiKeyHashes.sha256(token)));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
