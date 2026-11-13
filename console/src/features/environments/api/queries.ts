/**
 * 环境页的查询与变更（console-api-map.md「5 环境」）：环境、修订历史与一个修订；全部按需刷新。查询键以
 * `['admin', 租户, 项目, 'environments', …]` 开头。保存与删除的结果就在视野里（编辑页、确认对话框），失败就地显示。
 */
import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';

import { useProject, type CurrentProject } from '@/lib/access';
import { adminApi, unwrap } from '@/lib/api/client';
import { collectPages, PAGE_LIMIT } from '@/lib/api/pages';
import type { EnvironmentRequest } from '@/lib/api/types';

type Scope = Pick<CurrentProject, 'tenantId' | 'projectId'>;

const projectPath = ({ tenantId, projectId }: Scope) => ({ tenant_id: tenantId, project_id: projectId });
const page = (after: string | undefined) => ({ limit: PAGE_LIMIT, ...(after ? { after } : {}) });

export const environmentKeys = {
  all: (s: Scope) => ['admin', s.tenantId, s.projectId, 'environments'] as const,
  one: (s: Scope, id: string) => [...environmentKeys.all(s), id] as const,
  revisions: (s: Scope, id: string) => [...environmentKeys.one(s, id), 'revisions'] as const,
};

export const environmentsQuery = (s: Scope) =>
  queryOptions({
    queryKey: environmentKeys.all(s),
    queryFn: ({ signal }) =>
      collectPages((after) =>
        unwrap(
          adminApi().GET('/admin/v1/tenants/{tenant_id}/projects/{project_id}/environments', {
            params: { path: projectPath(s), query: page(after) },
            signal,
          }),
        ),
      ),
  });

export const environmentQuery = (s: Scope, id: string) =>
  queryOptions({
    queryKey: environmentKeys.one(s, id),
    queryFn: ({ signal }) =>
      unwrap(
        adminApi().GET('/admin/v1/tenants/{tenant_id}/projects/{project_id}/environments/{environment_id}', {
          params: { path: { ...projectPath(s), environment_id: id } },
          signal,
        }),
      ),
  });

/** 修订历史（修订号从小到大）。 */
export const revisionsQuery = (s: Scope, id: string) =>
  queryOptions({
    queryKey: environmentKeys.revisions(s, id),
    queryFn: ({ signal }) =>
      collectPages((after) =>
        unwrap(
          adminApi().GET(
            '/admin/v1/tenants/{tenant_id}/projects/{project_id}/environments/{environment_id}/revisions',
            { params: { path: { ...projectPath(s), environment_id: id }, query: page(after) }, signal },
          ),
        ),
      ),
  });

function useScope() {
  const { tenantId, projectId } = useProject();
  return { tenantId, projectId };
}

/** 创建环境（修订从 1 开始）。 */
export function useCreateEnvironment() {
  const scope = useScope();
  const queryClient = useQueryClient();
  return useMutation({
    meta: { notifyError: false },
    mutationFn: (body: EnvironmentRequest) =>
      unwrap(
        adminApi().POST('/admin/v1/tenants/{tenant_id}/projects/{project_id}/environments', {
          params: { path: projectPath(scope) },
          body,
        }),
      ),
    onSuccess: (env) => queryClient.setQueryData(environmentKeys.one(scope, env.id), env),
    onSettled: () => queryClient.invalidateQueries({ queryKey: environmentKeys.all(scope), exact: true }),
  });
}

/** 整体替换环境：档位、规格或白名单变化时修订号加一。 */
export function useUpdateEnvironment(id: string) {
  const scope = useScope();
  const queryClient = useQueryClient();
  return useMutation({
    meta: { notifyError: false },
    mutationFn: (body: EnvironmentRequest) =>
      unwrap(
        adminApi().PUT('/admin/v1/tenants/{tenant_id}/projects/{project_id}/environments/{environment_id}', {
          params: { path: { ...projectPath(scope), environment_id: id } },
          body,
        }),
      ),
    onSuccess: (env) => queryClient.setQueryData(environmentKeys.one(scope, id), env),
    onSettled: () => queryClient.invalidateQueries({ queryKey: environmentKeys.all(scope) }),
  });
}

export function useDeleteEnvironment() {
  const scope = useScope();
  const queryClient = useQueryClient();
  return useMutation({
    meta: { notifyError: false },
    mutationFn: (id: string) =>
      unwrap(
        adminApi().DELETE(
          '/admin/v1/tenants/{tenant_id}/projects/{project_id}/environments/{environment_id}',
          {
            params: { path: { ...projectPath(scope), environment_id: id } },
          },
        ),
      ),
    onSuccess: (_, id) => queryClient.removeQueries({ queryKey: environmentKeys.one(scope, id) }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: environmentKeys.all(scope), exact: true }),
  });
}
