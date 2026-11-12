package cn.refinex.meridian.security.rbac;

import static cn.refinex.meridian.security.rbac.Permission.AGENT_READ;
import static cn.refinex.meridian.security.rbac.Permission.AGENT_WRITE;
import static cn.refinex.meridian.security.rbac.Permission.API_KEY_READ;
import static cn.refinex.meridian.security.rbac.Permission.API_KEY_WRITE;
import static cn.refinex.meridian.security.rbac.Permission.MEMBER_READ;
import static cn.refinex.meridian.security.rbac.Permission.MEMBER_WRITE;
import static cn.refinex.meridian.security.rbac.Permission.MODEL_INVOKE;
import static cn.refinex.meridian.security.rbac.Permission.MODEL_READ;
import static cn.refinex.meridian.security.rbac.Permission.MODEL_WRITE;
import static cn.refinex.meridian.security.rbac.Permission.PENDING_ACTION_DECIDE;
import static cn.refinex.meridian.security.rbac.Permission.PROJECT_CREATE;
import static cn.refinex.meridian.security.rbac.Permission.PROJECT_DELETE;
import static cn.refinex.meridian.security.rbac.Permission.PROJECT_READ;
import static cn.refinex.meridian.security.rbac.Permission.PROJECT_UPDATE;
import static cn.refinex.meridian.security.rbac.Permission.SECRET_READ;
import static cn.refinex.meridian.security.rbac.Permission.SECRET_WRITE;
import static cn.refinex.meridian.security.rbac.Permission.SESSION_READ;
import static cn.refinex.meridian.security.rbac.Permission.SESSION_WRITE;
import static cn.refinex.meridian.security.rbac.Permission.TENANT_CREATE;
import static cn.refinex.meridian.security.rbac.Permission.TENANT_LIST_ALL;
import static cn.refinex.meridian.security.rbac.Permission.TENANT_QUOTA_UPDATE;
import static cn.refinex.meridian.security.rbac.Permission.TENANT_READ;
import static cn.refinex.meridian.security.rbac.Permission.TENANT_UPDATE;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 角色矩阵：每个角色能执行哪些类别的操作（架构设计「授权」，ADR-0042）。授权判定的唯一数据来源，业务代码不写角色判断。
 *
 * <p>新增操作类别时在 {@link Permission} 中加常量、在这里给角色授权，并同步 control-plane 的角色矩阵测试表。
 */
public final class RoleMatrix {

    private static final Map<Role, Set<Permission>> MATRIX = new EnumMap<>(Role.class);

    static {
        MATRIX.put(Role.PLATFORM_ADMIN, EnumSet.of(TENANT_CREATE, TENANT_LIST_ALL, TENANT_QUOTA_UPDATE, TENANT_READ));
        // 租户所有者："全部，包括计费和删除租户"；平台级操作除外。按层级计算，新增的租户级、项目级类别自动归所有者
        Set<Permission> owner = EnumSet.noneOf(Permission.class);
        for (Permission permission : Permission.values()) {
            if (permission.level() == Permission.Level.TENANT || permission.level() == Permission.Level.PROJECT) {
                owner.add(permission);
            }
        }
        MATRIX.put(Role.TENANT_OWNER, owner);
        // 租户管理员："管理项目、成员、模型、凭据、预算"：不能删除租户、不能管理所有者、不审批（模型与凭据 ADR-0045）
        MATRIX.put(
                Role.TENANT_ADMIN,
                EnumSet.of(
                        TENANT_READ,
                        TENANT_UPDATE,
                        MEMBER_READ,
                        MEMBER_WRITE,
                        PROJECT_CREATE,
                        PROJECT_READ,
                        PROJECT_UPDATE,
                        PROJECT_DELETE,
                        API_KEY_READ,
                        API_KEY_WRITE,
                        MODEL_READ,
                        MODEL_WRITE,
                        SECRET_READ,
                        SECRET_WRITE,
                        SESSION_READ,
                        AGENT_READ));
        // 项目开发者"可用 Playground"：公开 API 的读写与模型直通（ADR-0044）；查看模型目录以选择智能体的模型（ADR-0045）；
        // "管理本项目的智能体、环境、技能"（ADR-0047）
        MATRIX.put(
                Role.PROJECT_DEVELOPER,
                EnumSet.of(
                        TENANT_READ,
                        PROJECT_READ,
                        API_KEY_READ,
                        API_KEY_WRITE,
                        SESSION_READ,
                        SESSION_WRITE,
                        MODEL_INVOKE,
                        MODEL_READ,
                        AGENT_READ,
                        AGENT_WRITE));
        // 观察者"只读，包括会话回放"；凭据的元数据不对观察者开放
        MATRIX.put(
                Role.PROJECT_VIEWER,
                EnumSet.of(TENANT_READ, PROJECT_READ, API_KEY_READ, SESSION_READ, MODEL_READ, AGENT_READ));
        MATRIX.put(Role.APPROVER, EnumSet.of(TENANT_READ, PROJECT_READ, PENDING_ACTION_DECIDE, SESSION_READ));
    }

    private RoleMatrix() {}

    /**
     * 角色是否能执行某类操作（不考虑作用范围）。
     *
     * @param role 角色
     * @param permission 操作类别
     * @return 能执行时为 true
     */
    public static boolean allows(Role role, Permission permission) {
        return MATRIX.get(role).contains(permission);
    }
}
