import { useMemo, useState } from 'react';

import { ErrorState } from '@/components/domain/error-state';
import { PermissionGate } from '@/components/domain/permission-gate';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';

import type { AgentDefinitionForm, DraftMeta } from '../fixtures/agent-definition';
import { EditPage, type EditGroup } from '../templates/edit-page';

import {
  BasicSection,
  ExtensionsSection,
  KnowledgeSection,
  LimitsSection,
  ModelSection,
  PromptSection,
  SubagentsSection,
  ToolsSection,
} from './sections';
import { groupOf, validate } from './validate';

const GROUPS: readonly Omit<EditGroup, 'invalid'>[] = [
  { id: 'basic', label: '基本信息' },
  { id: 'model', label: '模型' },
  { id: 'prompt', label: '提示词' },
  { id: 'tools', label: '工具与权限' },
  { id: 'knowledge', label: '知识库' },
  { id: 'extensions', label: '技能与 MCP' },
  { id: 'subagents', label: '子智能体' },
  { id: 'limits', label: '运行限制' },
];

const READ_ONLY = '编辑智能体需要本项目的开发者或所有者角色，请联系项目管理员。';

export interface AgentEditProps {
  /** 草稿规格；加载中、加载失败时为 null。 */
  draft: AgentDefinitionForm | null;
  meta: DraftMeta;
  loading?: boolean;
  loadError?: unknown;
  /** 当前用户能否编辑（AGENT_WRITE）。 */
  canEdit?: boolean;
  /** 打开时就显示校验结果（故事"校验错误"）。 */
  showErrors?: boolean;
  /** 打开时已有未保存的更改（故事"有未保存的更改"）。 */
  initialEdits?: Partial<AgentDefinitionForm>;
}

/**
 * 智能体编辑（编辑页模板）：左侧八个表单分组，右侧表单最大 880px，底部固定保存栏。保存草稿时全量校验，
 * 错误就近显示并聚焦第一个；发布前写明对进行中会话的影响。正式页面（T22）用 React Hook Form 与 Zod。
 */
export function AgentEdit({
  draft,
  meta,
  loading = false,
  loadError,
  canEdit = true,
  showErrors = false,
  initialEdits,
}: AgentEditProps) {
  const [baseline, setBaseline] = useState(draft);
  const [form, setForm] = useState(() => (draft ? { ...draft, ...initialEdits } : null));
  const [submitted, setSubmitted] = useState(showErrors);
  const [saving, setSaving] = useState(false);
  const [savedAt, setSavedAt] = useState(meta.savedAt);
  const [publishOpen, setPublishOpen] = useState(false);

  const errors = useMemo(() => (form && submitted ? validate(form) : {}), [form, submitted]);
  const errorFields = Object.keys(errors);
  const dirty = form !== null && baseline !== null && JSON.stringify(form) !== JSON.stringify(baseline);
  const invalidGroups = new Set(errorFields.map(groupOf));
  const name = baseline?.name ?? '';

  const save = () => {
    if (!form) return;
    setSubmitted(true);
    const found = Object.keys(validate(form));
    if (found[0]) {
      // 提交时聚焦第一个错误字段。
      const el = document.getElementById(found[0]);
      el?.scrollIntoView({ block: 'center' });
      el?.focus({ preventScroll: true });
      return;
    }
    setSaving(true);
    window.setTimeout(() => {
      setBaseline(form);
      setSavedAt(new Date().toISOString());
      setSaving(false);
    }, 600);
  };

  const sectionProps = form
    ? {
        form,
        errors,
        readOnly: !canEdit,
        onChange: (patch: Partial<AgentDefinitionForm>) => setForm((f) => (f ? { ...f, ...patch } : f)),
      }
    : null;

  return (
    <>
      <EditPage
        title={`编辑 ${name || '智能体'}`}
        loading={loading}
        meta={
          <>
            <Badge tone="accent">草稿 v{meta.draftVersion}</Badge>
            <span>基于已发布的 v{meta.baseVersion}</span>
          </>
        }
        actions={
          <>
            <Button>与 v{meta.baseVersion} 对比</Button>
            <PermissionGate allowed={canEdit} mode="disable" reason={READ_ONLY}>
              <Button variant="primary" onClick={() => setPublishOpen(true)}>
                发布 v{meta.draftVersion}
              </Button>
            </PermissionGate>
          </>
        }
        groups={GROUPS.map((g) => ({ ...g, invalid: invalidGroups.has(g.id) }))}
        error={
          loadError ? (
            <ErrorState
              title="无法打开这个草稿"
              error={loadError}
              onRetry={() => undefined}
              className="flex-1"
            />
          ) : undefined
        }
        saveBar={{
          dirty,
          saving,
          savedAt,
          errorCount: errorFields.length,
          saveLabel: '保存草稿',
          onSave: save,
          onDiscard: () => {
            setForm(baseline);
            setSubmitted(false);
          },
          readOnly: canEdit ? undefined : READ_ONLY,
        }}
      >
        {sectionProps && (
          <>
            <BasicSection {...sectionProps} />
            <ModelSection {...sectionProps} />
            <PromptSection {...sectionProps} />
            <ToolsSection {...sectionProps} />
            <KnowledgeSection {...sectionProps} />
            <ExtensionsSection {...sectionProps} />
            <SubagentsSection {...sectionProps} />
            <LimitsSection {...sectionProps} />
          </>
        )}
      </EditPage>
      <AlertDialog open={publishOpen} onOpenChange={setPublishOpen}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              发布 {name} v{meta.draftVersion}
            </AlertDialogTitle>
            <AlertDialogDescription asChild>
              <div className="flex flex-col gap-2">
                <p>
                  发布后新建的会话使用 v{meta.draftVersion}；正在使用 v{meta.baseVersion} 的{' '}
                  {meta.activeSessions} 个会话不受影响。
                </p>
                {dirty && <p>未保存的更改会先保存进草稿。</p>}
              </div>
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={() => setPublishOpen(false)}>
              发布 v{meta.draftVersion}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}
