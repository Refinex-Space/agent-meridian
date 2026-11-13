package cn.refinex.meridian.controlplane.member.domain;

import cn.refinex.meridian.security.rbac.Role;
import java.util.List;

/**
 * 控制台用户在一个租户中的成员身份（{@code GET /admin/v1/me}，ADR-0053）。
 *
 * @param tenantId 租户 ID
 * @param tenantName 租户名称
 * @param memberId 成员 ID
 * @param roles 角色绑定，按绑定 ID 排序
 */
public record Membership(String tenantId, String tenantName, String memberId, List<BoundRole> roles) {

    /**
     * 成员身份中的一个角色。
     *
     * @param bindingId 角色绑定 ID
     * @param role 角色
     * @param projectId 项目级角色的项目；租户级为空
     */
    public record BoundRole(String bindingId, Role role, String projectId) {}
}
