package cn.refinex.meridian.security.oidc;

import cn.refinex.meridian.security.apikey.ApiKeyScope;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 控制台用户在一个项目上的权限（ADR-0044）：项目所属租户与公开 API 的作用域。
 *
 * @param tenantId 项目所属租户；必填
 * @param projectId 项目；必填
 * @param scopes 作用域；用户在项目中没有角色时为空集合
 */
public record ProjectAccess(String tenantId, String projectId, Set<ApiKeyScope> scopes) {

    /**
     * 创建权限。
     *
     * @throws NullPointerException 缺少必填字段时
     */
    public ProjectAccess {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(projectId, "projectId");
        scopes = scopes == null || scopes.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(scopes));
    }
}
