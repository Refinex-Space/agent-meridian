package cn.refinex.meridian.controlplane.member.app;

import cn.refinex.meridian.common.context.PlatformContexts;
import cn.refinex.meridian.controlplane.config.ControlPlaneProperties;
import cn.refinex.meridian.controlplane.member.infra.RoleBindingRepository;
import cn.refinex.meridian.controlplane.tenant.app.TenantService;
import cn.refinex.meridian.controlplane.web.Scopes;
import cn.refinex.meridian.security.oidc.ConsoleUser;
import cn.refinex.meridian.security.rbac.Grant;
import cn.refinex.meridian.security.rbac.GrantSource;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 角色绑定的来源（{@link GrantSource} 的实现，ADR-0042）。每次判定前先绑定该用户按邮箱收到的邀请（{@link MemberBinding}，ADR-0053），
 * 首次登录的用户因此立即获得邀请时授予的角色。平台管理员来自配置（主体标识列表，或令牌中的组，ADR-0043），租户内的角色来自 {@code cp.role_bindings}。已删除的
 * 租户中没有任何角色，删除之后的一切管理操作都被拒绝。
 */
@Component
public class DatabaseGrantSource implements GrantSource {

    private final RoleBindingRepository bindings;
    private final MemberBinding invitations;
    private final TenantService tenants;
    private final Set<String> platformAdmins;
    private final Set<String> platformAdminGroups;

    /**
     * 创建来源。
     *
     * @param bindings 角色绑定仓储
     * @param invitations 邀请的绑定
     * @param tenants 租户用例，判断租户是否存在
     * @param properties control-plane 配置，取平台管理员
     */
    public DatabaseGrantSource(
            RoleBindingRepository bindings,
            MemberBinding invitations,
            TenantService tenants,
            ControlPlaneProperties properties) {
        this.bindings = bindings;
        this.invitations = invitations;
        this.tenants = tenants;
        this.platformAdmins = Set.copyOf(properties.platformAdmins());
        this.platformAdminGroups = Set.copyOf(properties.platformAdminGroups());
    }

    @Override
    public Set<Grant> grants(ConsoleUser user, String tenantId) {
        invitations.bindInvitations(user);
        String subject = user.subject();
        Set<Grant> grants = new HashSet<>();
        if (platformAdmins.contains(subject) || user.groups().stream().anyMatch(platformAdminGroups::contains)) {
            grants.add(Grant.platformAdmin());
        }
        if (tenantId != null && tenants.exists(tenantId)) {
            PlatformContexts.run(
                    Scopes.tenant(tenantId),
                    () -> bindings.listForSubject(subject)
                            .forEach(binding -> grants.add(new Grant(binding.role(), binding.projectId()))));
        }
        return grants;
    }
}
