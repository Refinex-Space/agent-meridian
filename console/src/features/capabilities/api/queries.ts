/**
 * 能力页的查询与变更（console-api-map.md「6 能力」）：项目的技能与版本、MCP 服务。全部按需刷新。查询键以
 * `['admin', 租户, 项目, 'skills' | 'mcp-servers', …]` 开头，与智能体编辑页的资源选择器同一个键：上传或登记之后选择器里立即可见。
 */
import { queryOptions, useMutation, useQueryClient } from '@tanstack/react-query';

import { useProject, type CurrentProject } from '@/lib/access';
import { adminApi, unwrap } from '@/lib/api/client';
import { collectPages, PAGE_LIMIT } from '@/lib/api/pages';
import type { McpServerRequest, SkillPackage } from '@/lib/api/types';

type Scope = Pick<CurrentProject, 'tenantId' | 'projectId'>;

const projectPath = ({ tenantId, projectId }: Scope) => ({ tenant_id: tenantId, project_id: projectId });
const page = (after: string | undefined) => ({ limit: PAGE_LIMIT, ...(after ? { after } : {}) });

export const skillKeys = {
  all: (s: Scope) => ['admin', s.tenantId, s.projectId, 'skills'] as const,
  one: (s: Scope, id: string) => [...skillKeys.all(s), id] as const,
  versions: (s: Scope, id: string) => [...skillKeys.one(s, id), 'versions'] as const,
};

export const mcpKeys = {
  all: (s: Scope) => ['admin', s.tenantId, s.projectId, 'mcp-servers'] as const,
  one: (s: Scope, id: string) => [...mcpKeys.all(s), id] as const,
};

/** 项目的全部技能（按创建时间），带引用方。 */
export const skillsQuery = (s: Scope) =>
  queryOptions({
    queryKey: skillKeys.all(s),
    queryFn: ({ signal }) =>
      collectPages((after) =>
        unwrap(
          adminApi().GET('/admin/v1/tenants/{tenant_id}/projects/{project_id}/skills', {
            params: { path: projectPath(s), query: page(after) },
            signal,
          }),
        ),
      ),
  });

export const skillQuery = (s: Scope, id: string) =>
  queryOptions({
    queryKey: skillKeys.one(s, id),
    queryFn: ({ signal }) =>
      unwrap(
        adminApi().GET('/admin/v1/tenants/{tenant_id}/projects/{project_id}/skills/{skill_id}', {
          params: { path: { ...projectPath(s), skill_id: id } },
          signal,
        }),
      ),
  });

/** 技能的全部版本（版本号从小到大）。 */
export const skillVersionsQuery = (s: Scope, id: string) =>
  queryOptions({
    queryKey: skillKeys.versions(s, id),
    queryFn: ({ signal }) =>
      collectPages((after) =>
        unwrap(
          adminApi().GET('/admin/v1/tenants/{tenant_id}/projects/{project_id}/skills/{skill_id}/versions', {
            params: { path: { ...projectPath(s), skill_id: id }, query: page(after) },
            signal,
          }),
        ),
      ),
  });

/** 项目的全部 MCP 服务（按创建时间），带工具清单与连接状态。 */
export const mcpServersQuery = (s: Scope) =>
  queryOptions({
    queryKey: mcpKeys.all(s),
    queryFn: ({ signal }) =>
      collectPages((after) =>
        unwrap(
          adminApi().GET('/admin/v1/tenants/{tenant_id}/projects/{project_id}/mcp-servers', {
            params: { path: projectPath(s), query: page(after) },
            signal,
          }),
        ),
      ),
  });

export const mcpServerQuery = (s: Scope, id: string) =>
  queryOptions({
    queryKey: mcpKeys.one(s, id),
    queryFn: ({ signal }) =>
      unwrap(
        adminApi().GET('/admin/v1/tenants/{tenant_id}/projects/{project_id}/mcp-servers/{mcp_server_id}', {
          params: { path: { ...projectPath(s), mcp_server_id: id } },
          signal,
        }),
      ),
  });

function useScope(): Scope {
  const { tenantId, projectId } = useProject();
  return { tenantId, projectId };
}

/** 上传技能（版本 1）。结果在对话框里，失败就地显示。 */
export function useCreateSkill() {
  const scope = useScope();
  const queryClient = useQueryClient();
  return useMutation({
    meta: { notifyError: false },
    mutationFn: (body: SkillPackage) =>
      unwrap(
        adminApi().POST('/admin/v1/tenants/{tenant_id}/projects/{project_id}/skills', {
          params: { path: projectPath(scope) },
          body,
        }),
      ),
    onSuccess: (skill) => queryClient.setQueryData(skillKeys.one(scope, skill.id), skill),
    onSettled: () => queryClient.invalidateQueries({ queryKey: skillKeys.all(scope), exact: true }),
  });
}

/** 上传新版本：frontmatter 的 `name` 必须与技能名一致。 */
export function useCreateSkillVersion(id: string) {
  const scope = useScope();
  const queryClient = useQueryClient();
  return useMutation({
    meta: { notifyError: false },
    mutationFn: (body: SkillPackage) =>
      unwrap(
        adminApi().POST('/admin/v1/tenants/{tenant_id}/projects/{project_id}/skills/{skill_id}/versions', {
          params: { path: { ...projectPath(scope), skill_id: id } },
          body,
        }),
      ),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: skillKeys.one(scope, id) }),
        queryClient.invalidateQueries({ queryKey: skillKeys.all(scope), exact: true }),
      ]),
  });
}

/** 删除技能；被已发布的智能体版本引用时 409 `skill.in_use`。 */
export function useDeleteSkill() {
  const scope = useScope();
  const queryClient = useQueryClient();
  return useMutation({
    meta: { notifyError: false },
    mutationFn: (id: string) =>
      unwrap(
        adminApi().DELETE('/admin/v1/tenants/{tenant_id}/projects/{project_id}/skills/{skill_id}', {
          params: { path: { ...projectPath(scope), skill_id: id } },
        }),
      ),
    onSuccess: (_, id) => queryClient.removeQueries({ queryKey: skillKeys.one(scope, id) }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: skillKeys.all(scope), exact: true }),
  });
}

/** 登记 MCP 服务：服务端连接一次、列出工具后才保存，连不上时 400 `mcp_server.probe_failed`。 */
export function useCreateMcpServer() {
  const scope = useScope();
  const queryClient = useQueryClient();
  return useMutation({
    meta: { notifyError: false },
    mutationFn: (body: McpServerRequest) =>
      unwrap(
        adminApi().POST('/admin/v1/tenants/{tenant_id}/projects/{project_id}/mcp-servers', {
          params: { path: projectPath(scope) },
          body,
        }),
      ),
    onSuccess: (server) => queryClient.setQueryData(mcpKeys.one(scope, server.id), server),
    onSettled: () => queryClient.invalidateQueries({ queryKey: mcpKeys.all(scope), exact: true }),
  });
}

/** 整体替换 MCP 服务（含副作用标注），服务端随之重新探测工具清单。 */
export function useUpdateMcpServer(id: string) {
  const scope = useScope();
  const queryClient = useQueryClient();
  return useMutation({
    meta: { notifyError: false },
    mutationFn: (body: McpServerRequest) =>
      unwrap(
        adminApi().PUT('/admin/v1/tenants/{tenant_id}/projects/{project_id}/mcp-servers/{mcp_server_id}', {
          params: { path: { ...projectPath(scope), mcp_server_id: id } },
          body,
        }),
      ),
    onSuccess: (server) => queryClient.setQueryData(mcpKeys.one(scope, id), server),
    onSettled: () => queryClient.invalidateQueries({ queryKey: mcpKeys.all(scope), exact: true }),
  });
}

/**
 * 重新探测工具清单。失败时（400 `mcp_server.probe_failed`）服务端把这次结果记进连接状态、清单不变，所以无论成败都重读。
 */
export function useProbeMcpServer() {
  const scope = useScope();
  const queryClient = useQueryClient();
  return useMutation({
    meta: { notifyError: false },
    mutationFn: (id: string) =>
      unwrap(
        adminApi().POST(
          '/admin/v1/tenants/{tenant_id}/projects/{project_id}/mcp-servers/{mcp_server_id}:probe',
          { params: { path: { ...projectPath(scope), mcp_server_id: id } } },
        ),
      ),
    onSettled: (_, __, id) =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: mcpKeys.one(scope, id) }),
        queryClient.invalidateQueries({ queryKey: mcpKeys.all(scope), exact: true }),
      ]),
  });
}

/** 删除 MCP 服务；被已发布的智能体版本引用时 409 `mcp_server.in_use`。 */
export function useDeleteMcpServer() {
  const scope = useScope();
  const queryClient = useQueryClient();
  return useMutation({
    meta: { notifyError: false },
    mutationFn: (id: string) =>
      unwrap(
        adminApi().DELETE('/admin/v1/tenants/{tenant_id}/projects/{project_id}/mcp-servers/{mcp_server_id}', {
          params: { path: { ...projectPath(scope), mcp_server_id: id } },
        }),
      ),
    onSuccess: (_, id) => queryClient.removeQueries({ queryKey: mcpKeys.one(scope, id) }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: mcpKeys.all(scope), exact: true }),
  });
}
