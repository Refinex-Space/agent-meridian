import { useNavigate } from '@tanstack/react-router';
import { useState } from 'react';

import { ErrorState } from '@/components/domain/error-state';
import { FormField, fieldAria } from '@/components/domain/form-field';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Textarea } from '@/components/ui/textarea';
import { useProject } from '@/lib/access';
import { ApiError } from '@/lib/api/errors';

import { useCreateAgent } from '../api/mutations';
import { AGENT_NAME, defaultSpec, type DraftSpec } from '../model/spec';

import { ResourceSelect } from './editor/resource-picker';
import { useAgentResources } from './editor/use-resources';

export interface CreateAgentDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** 复制已有智能体时：它的名字与当前版本的规格。 */
  copyFrom?: { name: string; spec: DraftSpec } | null;
}

/**
 * 新建智能体：写名字、说明，选主模型（规格中唯一的必填引用），建好草稿 v1 后进入编辑页。复制已有智能体时沿用它的规格。
 */
export function CreateAgentDialog({ open, onOpenChange, copyFrom }: CreateAgentDialogProps) {
  const { projectId } = useProject();
  const navigate = useNavigate();
  const resources = useAgentResources();
  const create = useCreateAgent();
  const [name, setName] = useState(copyFrom ? `${copyFrom.name}-copy`.slice(0, 64) : '');
  const [description, setDescription] = useState('');
  const [modelId, setModelId] = useState<string | null>(copyFrom?.spec.model.model_id ?? null);
  const [submitted, setSubmitted] = useState(false);

  const nameError = !submitted
    ? null
    : !name.trim()
      ? '写一个名字，项目内不能重复。'
      : !AGENT_NAME.test(name.trim())
        ? '小写字母开头，只用小写字母、数字、下划线与短横线，至多 64 个字符。'
        : create.error instanceof ApiError && create.error.problem?.code === 'agent.name_taken'
          ? '项目内已有同名的智能体，换一个名字。'
          : null;
  const modelError = submitted && !modelId ? '选择一个对话模型。' : null;
  const otherError =
    create.error && !(create.error instanceof ApiError && create.error.problem?.code === 'agent.name_taken')
      ? create.error
      : null;

  const submit = () => {
    setSubmitted(true);
    const trimmed = name.trim();
    if (!trimmed || !AGENT_NAME.test(trimmed) || !modelId) return;
    const spec = copyFrom
      ? { ...copyFrom.spec, model: { ...copyFrom.spec.model, model_id: modelId } }
      : defaultSpec(modelId);
    create.mutate(
      { name: trimmed, description, spec },
      {
        onSuccess: ({ agent, version }) => {
          onOpenChange(false);
          void navigate({
            to: '/projects/$projectId/agents/$agentId/versions/$version/edit',
            params: { projectId, agentId: agent.id, version: String(version.version) },
          });
        },
      },
    );
  };

  return (
    <Dialog open={open} onOpenChange={(next) => !create.isPending && onOpenChange(next)}>
      <DialogContent
        size="md"
        onOpenAutoFocus={(e) => {
          // 默认会聚焦第一个可聚焦元素（名称旁的说明图标）并弹出提示；直接聚焦名称输入框。
          e.preventDefault();
          document.getElementById('create-agent-name')?.focus();
        }}
      >
        <DialogHeader>
          <DialogTitle>{copyFrom ? `复制 ${copyFrom.name}` : '新建智能体'}</DialogTitle>
          <DialogDescription>
            {copyFrom
              ? '沿用当前版本的规格，建好草稿后进入编辑。'
              : '建好草稿后进入编辑，配置提示词、工具与权限后发布。'}
          </DialogDescription>
        </DialogHeader>
        <form
          id="create-agent"
          className="flex flex-col gap-4"
          onSubmit={(e) => {
            e.preventDefault();
            submit();
          }}
        >
          <FormField
            id="create-agent-name"
            label="名称"
            hint="项目内唯一；发布时写进规格，也是日志里的智能体名"
            error={nameError}
          >
            <Input
              id="create-agent-name"
              value={name}
              placeholder="support-assistant"
              className="font-mono"
              onChange={(e) => {
                setName(e.target.value);
                create.reset();
              }}
              {...fieldAria('create-agent-name', nameError)}
            />
          </FormField>
          <FormField id="create-agent-description" label="说明">
            <Textarea
              id="create-agent-description"
              rows={2}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
          </FormField>
          <FormField id="create-agent-model" label="主模型" error={modelError}>
            <ResourceSelect
              id="create-agent-model"
              value={modelId}
              onChange={setModelId}
              source={resources.models}
              searchLabel="搜索模型"
              emptyLabel="本租户的模型目录里还没有对话模型"
              invalid={Boolean(modelError)}
              describedBy={modelError ? 'create-agent-model-error' : undefined}
            />
          </FormField>
          {otherError ? <ErrorState size="inline" title="新建失败" error={otherError} /> : null}
        </form>
        <DialogFooter>
          <Button disabled={create.isPending} onClick={() => onOpenChange(false)}>
            取消
          </Button>
          <Button type="submit" form="create-agent" variant="primary" pending={create.isPending}>
            {copyFrom ? '复制并编辑' : '新建并编辑'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
