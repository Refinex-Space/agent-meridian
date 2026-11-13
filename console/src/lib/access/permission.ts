import type { PermissionName, Role } from '@/lib/api/types';

import { hasPermission, useProject, type CurrentProject } from './project';

/** 角色的显示名（与架构设计「授权」的角色表一致）。 */
export const ROLE_LABEL: Record<Role, string> = {
  tenant_owner: '租户所有者',
  tenant_admin: '租户管理员',
  project_developer: '项目开发者',
  project_viewer: '项目观察者',
  approver: '审批人',
};

/** 角色按权限从大到小排列，显示时依此排序。 */
const ROLE_ORDER: readonly Role[] = [
  'tenant_owner',
  'tenant_admin',
  'project_developer',
  'approver',
  'project_viewer',
];

/** 当前用户在项目中的角色，用顿号连接，例如"项目开发者、审批人"；没有角色时为空串。 */
export function describeRoles(roles: readonly Role[]): string {
  return ROLE_ORDER.filter((r) => roles.includes(r))
    .map((r) => ROLE_LABEL[r])
    .join('、');
}

/** 一个操作的权限判断结果，可以直接展开给 `PermissionGate`。 */
export interface PermissionCheck {
  allowed: boolean;
  /** 不能操作时的说明：当前角色、不能做什么、找谁授予。 */
  reason: string;
}

/** 无权限时的说明：写明用户现在的角色与不能做的事，不在前端复述角色矩阵。 */
export function permissionReason(project: CurrentProject, action: string): string {
  const roles = describeRoles(project.roles);
  const who = roles ? `你在${project.projectName}中是${roles}` : `你在${project.projectName}中没有角色`;
  return `${who}，不能${action}。请联系项目管理员授予所需的角色。`;
}

/**
 * 当前用户能否执行某个操作类别（`GET /admin/v1/me/access`），以及不能时的说明。`action` 写按钮所做的事，例如
 * "在 Playground 中对话"。
 *
 * @example
 * const create = usePermission('agent_write', '新建智能体');
 * <PermissionGate mode="disable" {...create}><Button>新建智能体</Button></PermissionGate>
 */
export function usePermission(permission: PermissionName, action: string): PermissionCheck {
  const project = useProject();
  const allowed = hasPermission(project, permission);
  return { allowed, reason: allowed ? '' : permissionReason(project, action) };
}
