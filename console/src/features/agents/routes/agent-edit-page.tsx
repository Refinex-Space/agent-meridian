import { useQueryClient, useSuspenseQuery } from '@tanstack/react-query';
import { Link, useNavigate } from '@tanstack/react-router';
import { useState } from 'react';
import { FormProvider } from 'react-hook-form';

import { DataRegion } from '@/components/domain/data-region';
import { ErrorState } from '@/components/domain/error-state';
import { LeaveGuard } from '@/components/domain/leave-guard';
import { PermissionGate } from '@/components/domain/permission-gate';
import { EditPage } from '@/components/templates';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { usePermission, useProject } from '@/lib/access';
import { ApiError } from '@/lib/api/errors';
import type { Agent, AgentVersion } from '@/lib/api/types';

import { usePublish } from '../api/mutations';
import { agentQuery, versionQuery } from '../api/queries';
import { PublishDialog } from '../components/editor/publish-dialog';
import {
  BasicSection,
  KnowledgeSection,
  ModelSection,
  PermissionsSection,
  PromptSection,
  RuntimeSection,
  SkillsSection,
  SubagentsSection,
  ToolsSection,
} from '../components/editor/sections';
import { useAgentEditor } from '../components/editor/use-agent-editor';
import { useAgentResources } from '../components/editor/use-resources';
import { AGENT_GROUPS } from '../model/fields';

export interface AgentEditPageProps {
  agentId: string;
  version: number;
}

const SKELETON_SAVE_BAR = {
  dirty: false,
  saveLabel: '保存草稿',
  onSave: () => undefined,
  onDiscard: () => undefined,
};

function Editor({ agent, version }: { agent: Agent; version: AgentVersion }) {
  const { projectId } = useProject();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const write = usePermission('agent_write', '编辑智能体');
  const published = version.status === 'published';
  const readOnly = !write.allowed || published;
  const resources = useAgentResources();
  const editor = useAgentEditor(agent, version, resources.context);
  const publish = usePublish(agent.id, version.version);
  const [publishOpen, setPublishOpen] = useState(false);
  const [publishError, setPublishError] = useState<unknown>(null);
  const base = agent.current_version?.version ?? null;

  const openPublish = async () => {
    const ok = editor.dirty ? await editor.save() : editor.check();
    if (!ok) return;
    setPublishError(null);
    publish.reset();
    setPublishOpen(true);
  };

  const confirmPublish = () => {
    publish.mutate(undefined, {
      onSuccess: () => {
        setPublishOpen(false);
        void queryClient.invalidateQueries({ queryKey: ['v1', projectId, 'session-stats'] });
        // 发布后版本不可修改：回到详情页，页头显示"已发布 vN"，不另弹通知。
        void navigate({
          to: '/projects/$projectId/agents/$agentId',
          params: { projectId, agentId: agent.id },
        });
      },
      onError: (error) => {
        // 有字段明细的校验错误放回表单的字段上；其他错误留在对话框里。
        if (error instanceof ApiError && (error.problem?.errors?.length ?? 0) > 0) {
          setPublishOpen(false);
          editor.applyServerError(error);
        } else setPublishError(error);
      },
    });
  };

  const sectionProps = { errors: editor.errors, readOnly, resources };
  const readOnlyReason = published
    ? `v${version.version} 已发布，不能修改；在详情页基于它创建新草稿。`
    : write.allowed
      ? undefined
      : write.reason;

  return (
    <FormProvider {...editor.form}>
      <LeaveGuard when={editor.dirty && !publish.isSuccess} />
      <EditPage
        title={`编辑 ${agent.name}`}
        meta={
          <>
            <Badge tone={published ? undefined : 'accent'}>
              {published ? '已发布' : '草稿'} v{version.version}
            </Badge>
            {base !== null && !published && <span>基于已发布的 v{base}</span>}
          </>
        }
        actions={
          <>
            {base !== null && !published && (
              <Button asChild>
                <Link
                  to="/projects/$projectId/agents/$agentId/compare"
                  params={{ projectId, agentId: agent.id }}
                  search={{ base: String(base), target: String(version.version) }}
                >
                  与 v{base} 对比
                </Link>
              </Button>
            )}
            {!published && (
              <PermissionGate mode="disable" allowed={write.allowed} reason={write.reason}>
                <Button
                  variant="primary"
                  pending={editor.saving && publishOpen}
                  onClick={() => void openPublish()}
                >
                  发布 v{version.version}
                </Button>
              </PermissionGate>
            )}
          </>
        }
        groups={AGENT_GROUPS.map((g) => ({ ...g, invalid: editor.invalidGroups.has(g.id) }))}
        notice={
          editor.rootError ? (
            <div className="border-t border-rule px-6 py-3">
              <ErrorState size="inline" title="保存失败" error={editor.rootError} />
            </div>
          ) : undefined
        }
        saveBar={{
          dirty: editor.dirty,
          saving: editor.saving,
          savedAt: editor.savedAt,
          errorCount: Object.keys(editor.errors).length,
          saveLabel: '保存草稿',
          onSave: () => void editor.save(),
          onDiscard: editor.discard,
          readOnly: readOnlyReason,
        }}
      >
        <div className="contents" onBlurCapture={editor.onBlurCapture}>
          <BasicSection {...sectionProps} />
          <ModelSection {...sectionProps} />
          <PromptSection {...sectionProps} />
          <ToolsSection {...sectionProps} />
          <PermissionsSection {...sectionProps} />
          <KnowledgeSection {...sectionProps} />
          <SkillsSection {...sectionProps} />
          <SubagentsSection {...sectionProps} />
          <RuntimeSection {...sectionProps} />
        </div>
      </EditPage>
      {!published && (
        <PublishDialog
          open={publishOpen}
          onOpenChange={setPublishOpen}
          agent={agent}
          version={version.version}
          names={resources.names}
          onConfirm={confirmPublish}
          pending={publish.isPending}
          error={publishError}
        />
      )}
    </FormProvider>
  );
}

function Loaded({ agentId, version }: AgentEditPageProps) {
  const { tenantId, projectId } = useProject();
  const scope = { tenantId, projectId };
  const agent = useSuspenseQuery(agentQuery(scope, agentId)).data;
  const draft = useSuspenseQuery(versionQuery(scope, agentId, version)).data;
  // 换了版本（基于另一版本新建草稿后跳转）时重建表单。
  return <Editor key={`${draft.id}`} agent={agent} version={draft} />;
}

/**
 * 智能体编辑（编辑页模板）：左侧九个表单分组，右侧表单最大 880px，底部固定保存栏。保存草稿时全量校验（与
 * `agent-spec.schema.json` 及发布规则一致），错误就近显示并聚焦第一个；服务端的字段错误同样定位到字段；发布前展示与当前版本的差异。
 */
export function AgentEditPage(props: AgentEditPageProps) {
  return (
    <DataRegion
      errorTitle="无法打开这个草稿"
      skeleton={
        <EditPage title="编辑智能体" loading groups={AGENT_GROUPS} saveBar={SKELETON_SAVE_BAR}>
          {null}
        </EditPage>
      }
    >
      <Loaded {...props} />
    </DataRegion>
  );
}
