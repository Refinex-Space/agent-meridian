/**
 * 当前用户、能进入的项目与在项目上的权限（console-api-map.md「应用外壳」）。查询的键以 `['admin', …]` 开头；
 * 公开 API 的查询键以 `['v1', 项目 ID, …]` 开头，切换项目时不会混用缓存。
 */
import { queryOptions } from '@tanstack/react-query';

import { adminApi, unwrap } from '@/lib/api/client';
import type { Me, Membership, MyAccess, Project, Role } from '@/lib/api/types';

/** 项目切换器的一项：项目与它所在的租户。 */
export type ProjectEntry = Pick<Project, 'id' | 'name' | 'tenant_id'> & { tenant_name: string };

/** 一个租户里当前用户能进入的项目。 */
export interface TenantProjects {
  tenant_id: string;
  tenant_name: string;
  projects: ProjectEntry[];
}

const PAGE_SIZE = 100;

/** 当前用户（控制台启动时先调用：首次登录时按邮箱绑定邀请也在这时完成）。 */
export const meQuery = () =>
  queryOptions({
    queryKey: ['admin', 'me'],
    queryFn: ({ signal }) => unwrap(adminApi().GET('/admin/v1/me', { signal })),
    staleTime: 5 * 60_000,
  });

async function listTenantProjects(tenantId: string, signal: AbortSignal): Promise<Project[]> {
  const projects: Project[] = [];
  let after: string | undefined;
  do {
    const page = await unwrap(
      adminApi().GET('/admin/v1/tenants/{tenant_id}/projects', {
        params: { path: { tenant_id: tenantId }, query: { limit: PAGE_SIZE, ...(after ? { after } : {}) } },
        signal,
      }),
    );
    projects.push(...page.data);
    after = page.has_more && page.next_cursor ? page.next_cursor : undefined;
  } while (after);
  return projects;
}

/** 有租户级角色时能进入租户的全部项目；只有项目级角色时只能进入绑定的项目。 */
function visibleProjects(membership: Membership, projects: readonly Project[]): ProjectEntry[] {
  const bindings = membership.role_bindings;
  const tenantWide = bindings.some((b) => b.project_id === null);
  const bound = new Set(bindings.map((b) => b.project_id));
  return projects
    .filter((p) => tenantWide || bound.has(p.id))
    .map((p) => ({ id: p.id, name: p.name, tenant_id: p.tenant_id, tenant_name: membership.tenant_name }));
}

/** 当前用户能进入的租户与项目，按租户分组（租户的顺序同 `GET /admin/v1/me`，项目按名称）。 */
export const projectsQuery = (me: Me) =>
  queryOptions({
    queryKey: ['admin', 'me', 'projects', me.subject],
    queryFn: async ({ signal }): Promise<TenantProjects[]> => {
      const groups = await Promise.all(
        me.memberships.map(async (m) => ({
          tenant_id: m.tenant_id,
          tenant_name: m.tenant_name,
          projects: visibleProjects(m, await listTenantProjects(m.tenant_id, signal)).sort((a, b) =>
            a.name.localeCompare(b.name, 'zh-CN'),
          ),
        })),
      );
      return groups.filter((g) => g.projects.length > 0);
    },
    staleTime: 5 * 60_000,
  });

/** 当前用户在项目上的操作类别与公开 API 作用域（按 control-plane 的角色矩阵算出，前端不复刻）。 */
export const accessQuery = (tenantId: string, projectId: string) =>
  queryOptions({
    queryKey: ['admin', 'me', 'access', tenantId, projectId],
    queryFn: ({ signal }): Promise<MyAccess> =>
      unwrap(
        adminApi().GET('/admin/v1/me/access', {
          params: { query: { tenant_id: tenantId, project_id: projectId } },
          signal,
        }),
      ),
    staleTime: 60_000,
  });

/** 用户在项目上的角色：租户级的角色与绑定到该项目的角色。 */
export function rolesInProject(me: Me, tenantId: string, projectId: string): Role[] {
  const membership = me.memberships.find((m) => m.tenant_id === tenantId);
  if (!membership) return [];
  return membership.role_bindings
    .filter((b) => b.project_id === null || b.project_id === projectId)
    .map((b) => b.role);
}
