/**
 * 智能体页的变更：创建、改名、删除智能体，创建、保存、删除、发布版本。成功后让相关查询失效；结果就在视野里的（编辑页保存与发布、
 * 新建对话框）设 `notifyError: false`，由界面就地显示错误。
 */
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { useProject } from '@/lib/access';
import { adminApi, unwrap } from '@/lib/api/client';

import type { DraftSpec } from '../model/spec';

import { agentKeys } from './queries';

function useScope() {
  const { tenantId, projectId } = useProject();
  return { tenantId, projectId, path: { tenant_id: tenantId, project_id: projectId } };
}

/** 新建智能体并建好第一个草稿；草稿创建失败时智能体已存在，详情页会提示创建草稿。 */
export function useCreateAgent() {
  const scope = useScope();
  const queryClient = useQueryClient();
  return useMutation({
    meta: { notifyError: false },
    mutationFn: async (input: { name: string; description: string; spec: DraftSpec }) => {
      const agent = await unwrap(
        adminApi().POST('/admin/v1/tenants/{tenant_id}/projects/{project_id}/agents', {
          params: { path: scope.path },
          body: { name: input.name, description: input.description },
        }),
      );
      const version = await unwrap(
        adminApi().POST('/admin/v1/tenants/{tenant_id}/projects/{project_id}/agents/{agent_id}/versions', {
          params: { path: { ...scope.path, agent_id: agent.id } },
          body: { spec: input.spec },
        }),
      );
      return { agent, version };
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: agentKeys.all(scope) }),
  });
}

export function useUpdateAgent(agentId: string) {
  const scope = useScope();
  const queryClient = useQueryClient();
  return useMutation({
    meta: { notifyError: false },
    mutationFn: (body: { name?: string; description?: string }) =>
      unwrap(
        adminApi().PATCH('/admin/v1/tenants/{tenant_id}/projects/{project_id}/agents/{agent_id}', {
          params: { path: { ...scope.path, agent_id: agentId } },
          body,
        }),
      ),
    onSuccess: (agent) => queryClient.setQueryData(agentKeys.one(scope, agentId), agent),
    onSettled: () => queryClient.invalidateQueries({ queryKey: agentKeys.all(scope), exact: true }),
  });
}

export function useDeleteAgent() {
  const scope = useScope();
  const queryClient = useQueryClient();
  return useMutation({
    // 失败在确认对话框里就地显示（DangerConfirmDialog）。
    meta: { notifyError: false },
    mutationFn: (agentId: string) =>
      unwrap(
        adminApi().DELETE('/admin/v1/tenants/{tenant_id}/projects/{project_id}/agents/{agent_id}', {
          params: { path: { ...scope.path, agent_id: agentId } },
        }),
      ),
    onSuccess: (_, agentId) => queryClient.removeQueries({ queryKey: agentKeys.one(scope, agentId) }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: agentKeys.all(scope), exact: true }),
  });
}

/** 创建草稿（基于某个版本时传入它的规格）。 */
export function useCreateDraft(agentId: string) {
  const scope = useScope();
  const queryClient = useQueryClient();
  return useMutation({
    meta: { errorTitle: '创建草稿失败' },
    mutationFn: (spec: DraftSpec) =>
      unwrap(
        adminApi().POST('/admin/v1/tenants/{tenant_id}/projects/{project_id}/agents/{agent_id}/versions', {
          params: { path: { ...scope.path, agent_id: agentId } },
          body: { spec },
        }),
      ),
    onSuccess: (version) =>
      queryClient.setQueryData(agentKeys.version(scope, agentId, version.version), version),
    onSettled: () =>
      queryClient.invalidateQueries({ queryKey: agentKeys.versions(scope, agentId), exact: true }),
  });
}

/** 保存草稿（整体替换规格）。 */
export function useSaveDraft(agentId: string, version: number) {
  const scope = useScope();
  const queryClient = useQueryClient();
  return useMutation({
    meta: { notifyError: false },
    mutationFn: (spec: DraftSpec) =>
      unwrap(
        adminApi().PUT(
          '/admin/v1/tenants/{tenant_id}/projects/{project_id}/agents/{agent_id}/versions/{version}',
          { params: { path: { ...scope.path, agent_id: agentId, version } }, body: { spec } },
        ),
      ),
    onSuccess: (saved) => queryClient.setQueryData(agentKeys.version(scope, agentId, version), saved),
    onSettled: () =>
      queryClient.invalidateQueries({ queryKey: agentKeys.versions(scope, agentId), exact: true }),
  });
}

export function useDeleteDraft(agentId: string) {
  const scope = useScope();
  const queryClient = useQueryClient();
  return useMutation({
    meta: { notifyError: false },
    mutationFn: (version: number) =>
      unwrap(
        adminApi().DELETE(
          '/admin/v1/tenants/{tenant_id}/projects/{project_id}/agents/{agent_id}/versions/{version}',
          { params: { path: { ...scope.path, agent_id: agentId, version } } },
        ),
      ),
    onSuccess: (_, version) =>
      queryClient.removeQueries({ queryKey: agentKeys.version(scope, agentId, version) }),
    onSettled: () =>
      queryClient.invalidateQueries({ queryKey: agentKeys.versions(scope, agentId), exact: true }),
  });
}

export function usePublish(agentId: string, version: number) {
  const scope = useScope();
  const queryClient = useQueryClient();
  return useMutation({
    meta: { notifyError: false },
    mutationFn: () =>
      unwrap(
        adminApi().POST(
          '/admin/v1/tenants/{tenant_id}/projects/{project_id}/agents/{agent_id}/versions/{version}:publish',
          { params: { path: { ...scope.path, agent_id: agentId, version } } },
        ),
      ),
    onSuccess: (published) => queryClient.setQueryData(agentKeys.version(scope, agentId, version), published),
    // 当前版本变了：列表、智能体与版本都要重读。
    onSettled: () => queryClient.invalidateQueries({ queryKey: agentKeys.all(scope) }),
  });
}
