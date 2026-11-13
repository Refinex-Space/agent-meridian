import { useQueryClient } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { useCallback, useState } from 'react';

import { notifyError } from '@/components/domain/error-state';
import { useProject } from '@/lib/access';
import type { AgentVersion } from '@/lib/api/types';

import { defaultSpec, toDraftSpec } from '../model/spec';

import { useCreateDraft } from './mutations';
import { latestDraft, latestPublished, modelsQuery, versionsQuery } from './queries';

/**
 * "编辑定义"与"基于此版本创建草稿"：有草稿时打开最新的草稿；没有时基于给定的版本（默认当前发布的版本）建一个新草稿再打开。
 * 智能体还没有任何版本（创建草稿失败过）时，用模型目录里第一个对话模型建一个空白草稿。
 */
export function useOpenDraft(agentId: string) {
  const { tenantId, projectId } = useProject();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const createDraft = useCreateDraft(agentId);
  const [pending, setPending] = useState(false);

  const go = useCallback(
    (version: number) =>
      navigate({
        to: '/projects/$projectId/agents/$agentId/versions/$version/edit',
        params: { projectId, agentId, version: String(version) },
      }),
    [navigate, projectId, agentId],
  );

  /** 基于某个版本新建草稿并打开（版本对比页、版本表的行操作）。 */
  const fromVersion = useCallback(
    async (source: AgentVersion) => {
      const draft = await createDraft.mutateAsync(toDraftSpec(source.spec));
      await go(draft.version);
    },
    [createDraft, go],
  );

  /** 打开最新的草稿，没有时基于当前版本新建。 */
  const open = useCallback(async () => {
    setPending(true);
    // 创建草稿的失败已由变更发出通知；只通知读取失败与没有模型。
    let creating = false;
    try {
      const versions = await queryClient.fetchQuery(versionsQuery({ tenantId, projectId }, agentId));
      const draft = latestDraft(versions);
      if (draft) return await go(draft.version);
      const base = latestPublished(versions);
      creating = true;
      if (base) return await fromVersion(base);
      creating = false;
      const models = await queryClient.fetchQuery(modelsQuery(tenantId));
      const model = models.find((m) => m.kind === 'chat');
      if (!model) {
        throw new Error('本租户的模型目录里还没有对话模型，先在模型页登记一个。');
      }
      creating = true;
      const draftVersion = await createDraft.mutateAsync(defaultSpec(model.id));
      return await go(draftVersion.version);
    } catch (error) {
      if (!creating) notifyError(error, '无法打开草稿');
      return undefined;
    } finally {
      setPending(false);
    }
  }, [agentId, createDraft, fromVersion, go, projectId, queryClient, tenantId]);

  return { open, fromVersion, pending: pending || createDraft.isPending };
}
