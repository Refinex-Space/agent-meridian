package cn.refinex.meridian.security.rbac;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.ForbiddenException;
import cn.refinex.meridian.common.error.MeridianException;
import cn.refinex.meridian.security.oidc.ConsoleAuthErrorCode;
import cn.refinex.meridian.security.oidc.ConsoleUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.Set;

/**
 * 一次管理请求的授权上下文：用户、路径中的租户与该用户在其中的角色绑定。{@link PermissionInterceptor} 建立后写入请求属性，用例在
 * 需要额外判定时（例如授予所有者）经 {@link #require} 使用同一套矩阵，不另写角色判断。
 *
 * @param user 控制台用户；必填
 * @param tenantId 路径中的租户 ID；请求不针对某个租户时为空
 * @param grants 角色绑定；必填，可为空集合
 */
public record AccessContext(ConsoleUser user, String tenantId, Set<Grant> grants) {

    /** 请求属性的键。 */
    public static final String ATTRIBUTE = AccessContext.class.getName();

    /** 规整集合。 */
    public AccessContext {
        Objects.requireNonNull(user, "user");
        grants = Set.copyOf(grants);
    }

    /**
     * 能否执行某类操作。
     *
     * @param permission 操作类别
     * @param projectId 操作针对的项目；项目级类别必填，其他可为空
     * @return 能执行时为 true
     */
    public boolean permits(Permission permission, String projectId) {
        return switch (permission.level()) {
            case AUTHENTICATED -> true;
            case PLATFORM ->
                grants.stream()
                        .anyMatch(g ->
                                g.role().scope() == Role.Scope.PLATFORM && RoleMatrix.allows(g.role(), permission));
            case TENANT -> grants.stream().anyMatch(g -> RoleMatrix.allows(g.role(), permission));
            case PROJECT ->
                grants.stream()
                        .anyMatch(g -> RoleMatrix.allows(g.role(), permission)
                                && (g.role().scope() != Role.Scope.PROJECT
                                        || g.projectId().equals(projectId)));
        };
    }

    /**
     * 要求能执行某类操作。
     *
     * @param permission 操作类别
     * @param projectId 操作针对的项目；项目级类别必填，其他可为空
     * @throws ForbiddenException 不能执行时（{@code access.forbidden}）
     */
    public void require(Permission permission, String projectId) {
        if (!permits(permission, projectId)) {
            throw new ForbiddenException(CommonErrorCode.FORBIDDEN, "当前用户没有执行该操作的角色");
        }
    }

    /**
     * 当前请求的授权上下文。
     *
     * @param request 当前请求
     * @return 授权上下文
     * @throws MeridianException 请求没有经过 {@link PermissionInterceptor} 时（{@code auth.unauthenticated}）
     */
    public static AccessContext current(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof AccessContext context) {
            return context;
        }
        throw new MeridianException(ConsoleAuthErrorCode.UNAUTHENTICATED, "管理 API 需要控制台用户令牌");
    }
}
