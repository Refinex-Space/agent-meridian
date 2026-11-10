package cn.refinex.meridian.security.apikey;

import cn.refinex.meridian.common.context.PlatformContext;
import java.util.Set;

/**
 * 公开 API 上认证后的主体：业务系统的 API Key（{@link ApiKeyPrincipal}），或带 {@code Meridian-Project} 的控制台用户
 * （{@code oidc.ConsoleUserPrincipal}，ADR-0044）。两者都落在一个租户与项目上，按作用域授权、按自己的桶限流。
 */
public interface PublicApiPrincipal {

    /**
     * 租户 ID。
     *
     * @return 租户 ID
     */
    String tenantId();

    /**
     * 项目 ID。
     *
     * @return 项目 ID
     */
    String projectId();

    /**
     * 作用域。
     *
     * @return 作用域；可为空集合
     */
    Set<ApiKeyScope> scopes();

    /**
     * 每分钟请求上限。
     *
     * @return 上限；为空表示不限流
     */
    Integer rateLimitPerMinute();

    /**
     * 令牌桶名，Redis 键为 {@code ratelimit:} 加它。花括号是 Redis Cluster 的哈希标签，同一个桶落在同一个槽。
     *
     * @return 例如 {@code apikey:{key_…}}、{@code user:{主体}}
     */
    String rateLimitBucket();

    /**
     * 日志中标识主体的文字，不含凭据。
     *
     * @return 例如 API Key ID
     */
    String describe();

    /**
     * 本主体发起的请求的平台上下文。
     *
     * @return 平台上下文
     */
    PlatformContext toContext();

    /**
     * 是否有某个作用域。
     *
     * @param scope 作用域
     * @return 有时为 true
     */
    default boolean hasScope(ApiKeyScope scope) {
        return scopes().contains(scope);
    }
}
