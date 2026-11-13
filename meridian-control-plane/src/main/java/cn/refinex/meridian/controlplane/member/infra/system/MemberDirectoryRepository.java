package cn.refinex.meridian.controlplane.member.infra.system;

import cn.refinex.meridian.persistence.tenant.CrossTenant;
import cn.refinex.meridian.persistence.tenant.SystemRepository;
import cn.refinex.meridian.security.rbac.Role;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;

/**
 * 按用户跨租户查成员身份（ADR-0053）：当前用户所在的租户与角色（{@code GET /admin/v1/me}），首次登录时按邮箱找未绑定的邀请。
 */
@Repository
@CrossTenant(reason = "一个控制台用户可以是多个租户的成员：按用户标识或经过验证的邮箱跨租户查找他自己的成员身份")
public class MemberDirectoryRepository extends SystemRepository {

    /**
     * 创建仓储。
     *
     * @param dataSource 应用的数据源
     */
    public MemberDirectoryRepository(DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 各租户中邮箱相同（不区分大小写）、尚未绑定的邀请，走 {@code members_invitation_lookup_idx}；已删除的租户不算。
     *
     * @param email 经过验证的邮箱
     * @return 邀请，按租户 ID 排序
     */
    public List<Invitation> invitations(String email) {
        return jdbc().sql("""
                        SELECT m.id, m.tenant_id FROM cp.members m
                          JOIN cp.tenants t ON t.id = m.tenant_id AND t.deleted_at IS NULL
                         WHERE m.subject IS NULL AND lower(m.email) = lower(:email)
                         ORDER BY m.tenant_id
                        """)
                .param("email", email)
                .query((rs, row) -> new Invitation(rs.getString("id"), rs.getString("tenant_id")))
                .list();
    }

    /**
     * 用户所在的租户与在其中的角色绑定；已删除的租户不算。
     *
     * @param subject 用户标识
     * @return 成员身份，按租户 ID 排序
     */
    public List<Membership> memberships(String subject) {
        Map<String, Membership> byMember = new LinkedHashMap<>();
        jdbc().sql("""
                        SELECT m.id AS member_id, m.tenant_id, t.name AS tenant_name, b.id AS binding_id, b.role,
                               b.project_id
                          FROM cp.members m
                          JOIN cp.tenants t ON t.id = m.tenant_id AND t.deleted_at IS NULL
                          LEFT JOIN cp.role_bindings b ON b.member_id = m.id
                         WHERE m.subject = :subject
                         ORDER BY m.tenant_id, b.id
                        """)
                .param("subject", subject)
                .query((rs, row) -> {
                    String memberId = rs.getString("member_id");
                    String tenantId = rs.getString("tenant_id");
                    String tenantName = rs.getString("tenant_name");
                    Membership membership = byMember.computeIfAbsent(
                            memberId, id -> new Membership(tenantId, tenantName, id, new ArrayList<>()));
                    String bindingId = rs.getString("binding_id");
                    if (bindingId != null) {
                        membership
                                .roles()
                                .add(new BoundRole(
                                        bindingId,
                                        Role.fromValue(rs.getString("role")).orElseThrow(),
                                        rs.getString("project_id")));
                    }
                    return membership;
                })
                .list();
        return List.copyOf(byMember.values());
    }

    /**
     * 一条未绑定的邀请。
     *
     * @param memberId 成员 ID
     * @param tenantId 租户 ID
     */
    public record Invitation(String memberId, String tenantId) {}

    /**
     * 用户在一个租户中的成员身份。
     *
     * @param tenantId 租户 ID
     * @param tenantName 租户名称
     * @param memberId 成员 ID
     * @param roles 角色绑定，按绑定 ID 排序
     */
    public record Membership(String tenantId, String tenantName, String memberId, List<BoundRole> roles) {}

    /**
     * 成员身份中的一个角色。
     *
     * @param bindingId 角色绑定 ID
     * @param role 角色
     * @param projectId 项目级角色的项目；租户级为空
     */
    public record BoundRole(String bindingId, Role role, String projectId) {}
}
