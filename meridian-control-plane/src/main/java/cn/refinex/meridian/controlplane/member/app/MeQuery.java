package cn.refinex.meridian.controlplane.member.app;

import cn.refinex.meridian.controlplane.member.domain.Membership;
import cn.refinex.meridian.controlplane.member.infra.system.MemberDirectoryRepository;
import cn.refinex.meridian.security.apikey.ApiKeyScope;
import cn.refinex.meridian.security.oidc.ConsoleUser;
import cn.refinex.meridian.security.rbac.AccessContext;
import cn.refinex.meridian.security.rbac.Grant;
import cn.refinex.meridian.security.rbac.GrantSource;
import cn.refinex.meridian.security.rbac.Permission;
import cn.refinex.meridian.security.rbac.PublicApiScopes;
import cn.refinex.meridian.security.rbac.Role;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 当前控制台用户的查询（{@code GET /admin/v1/me}、{@code /me/access}，ADR-0053）：身份与成员身份，以及按同一套角色矩阵算出的权限，
 * 控制台据此显示或禁用操作，不在前端复刻角色矩阵。线程安全：无状态。
 */
@Service
public class MeQuery {

    private final MemberDirectoryRepository directory;
    private final GrantSource grants;

    /**
     * 创建查询。
     *
     * @param directory 跨租户的成员目录
     * @param grants 角色绑定的来源（同时绑定邀请）
     */
    public MeQuery(MemberDirectoryRepository directory, GrantSource grants) {
        this.directory = directory;
        this.grants = grants;
    }

    /**
     * 当前用户。
     *
     * @param user 控制台用户
     * @return 身份与成员身份
     */
    public Me me(ConsoleUser user) {
        boolean platformAdmin = grants.grants(user, null).stream().anyMatch(g -> g.role() == Role.PLATFORM_ADMIN);
        return new Me(user.subject(), user.verifiedEmail(), platformAdmin, directory.memberships(user.subject()));
    }

    /**
     * 当前用户在租户或项目上的权限。
     *
     * @param user 控制台用户
     * @param tenantId 租户 ID
     * @param projectId 项目 ID；可为空，此时项目级的操作类别只按租户级角色计，作用域为空
     * @return 权限
     */
    public Access access(ConsoleUser user, String tenantId, String projectId) {
        Set<Grant> granted = grants.grants(user, tenantId);
        AccessContext context = new AccessContext(user, tenantId, granted);
        List<Permission> permissions = granted.isEmpty()
                ? List.of()
                : Arrays.stream(Permission.values())
                        .filter(p -> p.level() != Permission.Level.AUTHENTICATED)
                        .filter(p -> context.permits(p, projectId))
                        .sorted(Comparator.comparing(Permission::name))
                        .toList();
        List<ApiKeyScope> scopes = projectId == null
                ? List.of()
                : PublicApiScopes.granted(context, projectId).stream()
                        .sorted(Comparator.comparing(ApiKeyScope::value))
                        .toList();
        return new Access(tenantId, projectId, permissions, scopes);
    }

    /**
     * 当前用户。
     *
     * @param subject 用户标识
     * @param email 经过验证的邮箱；可为空
     * @param platformAdmin 是否平台管理员
     * @param memberships 成员身份
     */
    public record Me(String subject, String email, boolean platformAdmin, List<Membership> memberships) {}

    /**
     * 权限。
     *
     * @param tenantId 租户 ID
     * @param projectId 项目 ID；可为空
     * @param permissions 操作类别
     * @param scopes 公开 API 的作用域
     */
    public record Access(String tenantId, String projectId, List<Permission> permissions, List<ApiKeyScope> scopes) {}
}
