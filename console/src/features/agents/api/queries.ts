/**
 * 智能体页的查询（console-api-map.md「4 智能体」）：管理 API 的智能体、版本、对比与规格引用的资源（模型目录、技能、MCP 服务），
 * 公开 API 的知识库、会话运行概况与会话列表。查询键以 `['admin', 租户, 项目, …]`（模型目录是租户级：`['admin', 租户, 'models']`）
 * 或 `['v1', 项目, …]` 开头。全部按需刷新，只有运行情况按 60 秒轮询。
 */
import { queryOptions } from '@tanstack/react-query';

import type { CurrentProject } from '@/lib/access';
import { adminApi, unwrap, v1Api } from '@/lib/api/client';
import { collectPages, PAGE_LIMIT } from '@/lib/api/pages';
import type { AgentVersion } from '@/lib/api/types';

type Scope = Pick<CurrentProject, 'tenantId' | 'projectId'>;

const projectPath = ({ tenantId, projectId }: Scope) => ({ tenant_id: tenantId, project_id: projectId });
const page = (after: string | undefined) => ({ limit: PAGE_LIMIT, ...(after ? { after } : {}) });

export const agentKeys = {
  all: (s: Scope) => ['admin', s.tenantId, s.projectId, 'agents'] as const,
  one: (s: Scope, agentId: string) => [...agentKeys.all(s), agentId] as const,
  versions: (s: Scope, agentId: string) => [...agentKeys.one(s, agentId), 'versions'] as const,
  version: (s: Scope, agentId: string, version: number) =>
    [...agentKeys.versions(s, agentId), version] as const,
  compare: (s: Scope, agentId: string, base: number, target: number) =>
    [...agentKeys.versions(s, agentId), 'compare', base, target] as const,
};

/** 项目的全部智能体（按创建时间）。 */
export const agentsQuery = (s: Scope) =>
  queryOptions({
    queryKey: agentKeys.all(s),
    queryFn: ({ signal }) =>
      collectPages((after) =>
        unwrap(
          adminApi().GET('/admin/v1/tenants/{tenant_id}/projects/{project_id}/agents', {
            params: { path: projectPath(s), query: page(after) },
            signal,
          }),
        ),
      ),
  });

export const agentQuery = (s: Scope, agentId: string) =>
  queryOptions({
    queryKey: agentKeys.one(s, agentId),
    queryFn: ({ signal }) =>
      unwrap(
        adminApi().GET('/admin/v1/tenants/{tenant_id}/projects/{project_id}/agents/{agent_id}', {
          params: { path: { ...projectPath(s), agent_id: agentId } },
          signal,
        }),
      ),
  });

/** 智能体的全部版本（版本号从小到大，含草稿）。 */
export const versionsQuery = (s: Scope, agentId: string) =>
  queryOptions({
    queryKey: agentKeys.versions(s, agentId),
    queryFn: ({ signal }) =>
      collectPages((after) =>
        unwrap(
          adminApi().GET('/admin/v1/tenants/{tenant_id}/projects/{project_id}/agents/{agent_id}/versions', {
            params: { path: { ...projectPath(s), agent_id: agentId }, query: page(after) },
            signal,
          }),
        ),
      ),
  });

export const versionQuery = (s: Scope, agentId: string, version: number) =>
  queryOptions({
    queryKey: agentKeys.version(s, agentId, version),
    queryFn: ({ signal }) =>
      unwrap(
        adminApi().GET(
          '/admin/v1/tenants/{tenant_id}/projects/{project_id}/agents/{agent_id}/versions/{version}',
          { params: { path: { ...projectPath(s), agent_id: agentId, version } }, signal },
        ),
      ),
  });

/** 两个版本规格的差异（草稿也可以参与）。 */
export const compareQuery = (s: Scope, agentId: string, base: number, target: number) =>
  queryOptions({
    queryKey: agentKeys.compare(s, agentId, base, target),
    queryFn: ({ signal }) =>
      unwrap(
        adminApi().GET(
          '/admin/v1/tenants/{tenant_id}/projects/{project_id}/agents/{agent_id}/versions:compare',
          { params: { path: { ...projectPath(s), agent_id: agentId }, query: { base, target } }, signal },
        ),
      ),
    // 草稿保存后差异会变，打开发布确认时总是重新读。
    staleTime: 0,
  });

/** 租户的模型目录（智能体只能用对话模型，Embedding 与 rerank 模型不出现在选择器里）。 */
export const modelsQuery = (tenantId: string) =>
  queryOptions({
    queryKey: ['admin', tenantId, 'models'],
    queryFn: ({ signal }) =>
      collectPages((after) =>
        unwrap(
          adminApi().GET('/admin/v1/tenants/{tenant_id}/models', {
            params: { path: { tenant_id: tenantId }, query: page(after) },
            signal,
          }),
        ),
      ),
  });

export const skillsQuery = (s: Scope) =>
  queryOptions({
    queryKey: ['admin', s.tenantId, s.projectId, 'skills'],
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

export const mcpServersQuery = (s: Scope) =>
  queryOptions({
    queryKey: ['admin', s.tenantId, s.projectId, 'mcp-servers'],
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

/** 项目的知识库（knowledge 服务的公开 API）。 */
export const knowledgeBasesQuery = (projectId: string) =>
  queryOptions({
    queryKey: ['v1', projectId, 'knowledge-bases'],
    queryFn: ({ signal }) =>
      collectPages((after) =>
        unwrap(v1Api(projectId).GET('/v1/knowledge-bases', { params: { query: page(after) }, signal })),
      ),
  });

/** 运行概况（使用中的会话数、运行中、24 小时失败率）；`agentId` 为空时是整个项目，列表页用它取每个智能体的数字。 */
export const agentStatsQuery = (projectId: string, agentId?: string) =>
  queryOptions({
    queryKey: ['v1', projectId, 'session-stats', '24h', agentId ?? 'all'],
    queryFn: ({ signal }) =>
      unwrap(
        v1Api(projectId).GET('/v1/session-stats', {
          params: { query: { window: '24h', ...(agentId ? { agent_id: agentId } : {}) } },
          signal,
        }),
      ),
    refetchInterval: 60_000,
  });

/** 绑定该智能体的会话（最近更新的在前，第一页）。 */
export const agentSessionsQuery = (projectId: string, agentId: string) =>
  queryOptions({
    queryKey: ['v1', projectId, 'sessions', { agent_id: agentId }],
    queryFn: ({ signal }) =>
      unwrap(
        v1Api(projectId).GET('/v1/sessions', {
          params: { query: { agent_id: agentId, limit: 50 } },
          signal,
        }),
      ),
  });

/** 最新的草稿（版本号最大的草稿）；没有时为 undefined。 */
export function latestDraft(versions: readonly AgentVersion[]): AgentVersion | undefined {
  return versions.findLast((v) => v.status === 'draft');
}

/** 当前发布的版本（版本号最大的已发布版本）。 */
export function latestPublished(versions: readonly AgentVersion[]): AgentVersion | undefined {
  return versions.findLast((v) => v.status === 'published');
}
