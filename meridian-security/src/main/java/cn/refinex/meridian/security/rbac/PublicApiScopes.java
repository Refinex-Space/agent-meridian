package cn.refinex.meridian.security.rbac;

import cn.refinex.meridian.security.apikey.ApiKeyScope;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 控制台用户在公开 API 上的作用域（ADR-0044）：公开 API 的处理器按 API Key 的作用域检查，控制台用户没有 Key，作用域由他在项目中
 * 的角色按 {@link RoleMatrix} 换算。映射只在这里定义；{@code files:write} 与会话写入同一操作类别（T17b，ADR-0050），{@code knowledge:*}
 * 对应知识库的两个操作类别（T18，ADR-0051）。
 */
public final class PublicApiScopes {

    private static final Map<ApiKeyScope, Permission> PERMISSIONS = new EnumMap<>(ApiKeyScope.class);

    static {
        PERMISSIONS.put(ApiKeyScope.SESSIONS_READ, Permission.SESSION_READ);
        PERMISSIONS.put(ApiKeyScope.SESSIONS_WRITE, Permission.SESSION_WRITE);
        PERMISSIONS.put(ApiKeyScope.MODELS_INVOKE, Permission.MODEL_INVOKE);
        PERMISSIONS.put(ApiKeyScope.FILES_WRITE, Permission.SESSION_WRITE);
        PERMISSIONS.put(ApiKeyScope.KNOWLEDGE_READ, Permission.KNOWLEDGE_READ);
        PERMISSIONS.put(ApiKeyScope.KNOWLEDGE_WRITE, Permission.KNOWLEDGE_WRITE);
    }

    private PublicApiScopes() {}

    /**
     * 用户在某个项目上的作用域。
     *
     * @param access 用户在项目所属租户中的授权上下文
     * @param projectId 项目 ID
     * @return 作用域；没有角色时为空集合
     */
    public static Set<ApiKeyScope> granted(AccessContext access, String projectId) {
        Set<ApiKeyScope> scopes = EnumSet.noneOf(ApiKeyScope.class);
        PERMISSIONS.forEach((scope, permission) -> {
            if (access.permits(permission, projectId)) {
                scopes.add(scope);
            }
        });
        return scopes;
    }
}
