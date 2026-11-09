package cn.refinex.meridian.controlplane.member.domain;

import cn.refinex.meridian.common.error.CommonErrorCode;
import cn.refinex.meridian.common.error.FieldViolation;
import cn.refinex.meridian.common.error.ValidationException;
import cn.refinex.meridian.security.rbac.Role;
import java.time.Instant;
import java.util.List;

/**
 * 角色绑定：某成员在租户或某项目中的一个角色（架构设计「授权」）。
 *
 * @param id 角色绑定 ID（{@code rbd_…}）
 * @param tenantId 所属租户 ID
 * @param memberId 成员 ID
 * @param role 角色，不会是平台管理员
 * @param projectId 项目 ID；租户级角色为空
 * @param createdAt 授予时间
 */
public record RoleBinding(String id, String tenantId, String memberId, Role role, String projectId, Instant createdAt) {

    /**
     * 校验角色与项目的组合（ADR-0042）：租户级角色不带项目，项目级角色必须带项目，平台管理员不能授予。
     *
     * @param role 角色
     * @param projectId 项目 ID；可为空
     * @throws ValidationException 组合不合法时
     */
    public static void validateScope(Role role, String projectId) {
        String message = switch (role.scope()) {
            case PLATFORM -> "平台管理员由配置给出，不能授予";
            case TENANT -> projectId == null ? null : "租户级角色不能指定 project_id";
            case PROJECT -> projectId == null ? "项目级角色必须指定 project_id" : null;
        };
        if (message != null) {
            throw new ValidationException(
                    CommonErrorCode.VALIDATION_FAILED, message, List.of(new FieldViolation("project_id", message)));
        }
    }
}
