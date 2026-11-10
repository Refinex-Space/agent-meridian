package cn.refinex.meridian.security.oidc;

import cn.refinex.meridian.common.context.PlatformContext;
import cn.refinex.meridian.security.apikey.ApiKeyScope;
import cn.refinex.meridian.security.apikey.PublicApiPrincipal;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 公开 API 上的控制台用户（ADR-0044）：OIDC 令牌加 {@code Meridian-Project} 认证，作用域由他在该项目中的角色换算，按用户限流。
 *
 * @param user 控制台用户；必填
 * @param tenantId 项目所属租户；必填
 * @param projectId 项目；必填
 * @param scopes 作用域；必填，非空
 * @param rateLimitPerMinute 每分钟上限（按用户计）
 */
public record ConsoleUserPrincipal(
        ConsoleUser user, String tenantId, String projectId, Set<ApiKeyScope> scopes, Integer rateLimitPerMinute)
        implements PublicApiPrincipal {

    /**
     * 创建主体。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public ConsoleUserPrincipal {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        scopes = scopes.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(scopes));
    }

    @Override
    public String rateLimitBucket() {
        return "user:{" + user.subject() + "}";
    }

    @Override
    public String describe() {
        return "user=" + user.subject();
    }

    @Override
    public PlatformContext toContext() {
        return PlatformContext.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .userId(user.subject())
                .build();
    }
}
