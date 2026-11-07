package cn.refinex.meridian.security.apikey;

import cn.refinex.meridian.common.context.PlatformContext;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 一个 API Key 认证后的主体。API Key 属于一个项目，所以主体总是同时有租户与项目（架构设计「业务接入示例」中
 * "end_user_id 的信任边界是 API Key 所属的项目"）。
 *
 * @param apiKeyId API Key ID（{@code key_…}）；必填，用作审计主体与幂等键的作用域
 * @param tenantId 租户 ID；必填
 * @param projectId 项目 ID；必填
 * @param scopes 作用域；可为空集合，表示什么都不能做
 */
public record ApiKeyPrincipal(String apiKeyId, String tenantId, String projectId, Set<ApiKeyScope> scopes) {

    /**
     * 创建主体。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public ApiKeyPrincipal {
        Objects.requireNonNull(apiKeyId, "apiKeyId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        scopes = scopes == null || scopes.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(scopes));
    }

    /**
     * 是否有某个作用域。
     *
     * @param scope 作用域
     * @return 有时为 true
     */
    public boolean hasScope(ApiKeyScope scope) {
        return scopes.contains(scope);
    }

    /**
     * 本主体发起的请求的平台上下文：租户、项目与 API Key。
     *
     * @return 平台上下文
     */
    public PlatformContext toContext() {
        return PlatformContext.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .apiKeyId(apiKeyId)
                .build();
    }
}
