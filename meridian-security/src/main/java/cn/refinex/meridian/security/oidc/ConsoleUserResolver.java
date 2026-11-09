package cn.refinex.meridian.security.oidc;

import java.util.Optional;

/**
 * 控制台用户令牌的来源：按 {@code Authorization: Bearer} 中的令牌找到用户。
 *
 * <p>T14a 提供开发期的 {@link DevConsoleUserResolver}（只在 {@code dev}、{@code test}）；T14b 提供校验 OIDC access token 的实现。
 * 实现可以阻塞，必须线程安全，不能记录令牌。
 */
public interface ConsoleUserResolver {

    /**
     * 解析令牌。
     *
     * @param token 令牌明文，非空
     * @return 用户；令牌无效或已过期时为空
     */
    Optional<ConsoleUser> resolve(String token);
}
