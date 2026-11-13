import { useQuery, type UseQueryResult } from '@tanstack/react-query';
import { useMemo } from 'react';

import { useProject } from '@/lib/access';
import type { CatalogModel, KnowledgeBase, McpServer, Skill } from '@/lib/api/types';
import { formatInteger, formatTokenLimit } from '@/lib/format';

import { knowledgeBasesQuery, mcpServersQuery, modelsQuery, skillsQuery } from '../../api/queries';
import type { SpecContext } from '../../model/spec';

import type { ResourceOption, ResourceSource } from '@/components/domain/resource-picker';

function source<T>(query: UseQueryResult<T[]>, map: (item: T) => ResourceOption): ResourceSource {
  return {
    options: (query.data ?? []).map(map),
    loading: query.isPending,
    error: query.error,
    onRetry: () => void query.refetch(),
  };
}

/** MCP 服务最近一次探测失败时的状态（连接状态 `last_check`）。 */
export function mcpStatus(server: McpServer): ResourceOption['status'] {
  return server.last_check && server.last_check.status !== 'ok'
    ? { kind: 'failure', label: '不可用' }
    : undefined;
}

export interface AgentResources {
  /** 对话模型（主模型、备用模型、子智能体模型、查询改写模型）。 */
  models: ResourceSource;
  knowledgeBases: ResourceSource;
  skills: ResourceSource;
  mcpServers: ResourceSource;
  /** 原始数据，供各分组显示名字、版本、工具清单。 */
  data: {
    models: readonly CatalogModel[];
    knowledgeBases: readonly KnowledgeBase[];
    skills: readonly Skill[];
    mcpServers: readonly McpServer[];
  };
  context: SpecContext;
  /** ID → 显示名（对比与摘要里把模型、知识库、技能、MCP 服务的 ID 换成名字）。 */
  names: ReadonlyMap<string, string>;
}

/**
 * 智能体规格引用的资源：租户的对话模型、项目的知识库、技能与 MCP 服务。各自独立加载，失败只影响对应的选择器。
 */
export function useAgentResources(): AgentResources {
  const { tenantId, projectId } = useProject();
  const scope = { tenantId, projectId };
  const models = useQuery(modelsQuery(tenantId));
  const knowledgeBases = useQuery(knowledgeBasesQuery(projectId));
  const skills = useQuery(skillsQuery(scope));
  const mcpServers = useQuery(mcpServersQuery(scope));

  return useMemo(() => {
    const chat = { ...models, data: models.data?.filter((m) => m.kind === 'chat') } as UseQueryResult<
      CatalogModel[]
    >;
    const names = new Map<string, string>();
    for (const m of models.data ?? []) names.set(m.id, m.name);
    for (const k of knowledgeBases.data ?? []) names.set(k.id, k.name);
    for (const s of skills.data ?? []) names.set(s.id, s.name);
    for (const s of mcpServers.data ?? []) names.set(s.id, s.name);
    return {
      models: source(chat, (m) => ({
        id: m.id,
        name: m.name,
        mono: true,
        detail: `上下文 ${formatTokenLimit(m.context_window)}`,
      })),
      knowledgeBases: source(knowledgeBases, (k) => ({
        id: k.id,
        name: k.name,
        detail: `${formatInteger(k.document_count)} 份文档`,
      })),
      skills: source(skills, (s) => ({
        id: s.id,
        name: s.name,
        mono: true,
        detail: `最新 v${s.latest_version}`,
      })),
      mcpServers: source(mcpServers, (s) => ({
        id: s.id,
        name: s.name,
        mono: true,
        detail: `${s.tools.length} 个工具`,
        status: mcpStatus(s),
      })),
      data: {
        models: models.data ?? [],
        knowledgeBases: knowledgeBases.data ?? [],
        skills: skills.data ?? [],
        mcpServers: mcpServers.data ?? [],
      },
      context: { mcpServerNames: new Map((mcpServers.data ?? []).map((s) => [s.id, s.name])) },
      names,
    };
  }, [models, knowledgeBases, skills, mcpServers]);
}
