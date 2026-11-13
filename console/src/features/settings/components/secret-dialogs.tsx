import { useId, useRef, useState, type SubmitEvent } from 'react';

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
import { Label } from '@/components/ui/label';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { useProject } from '@/lib/access';
import { ApiError } from '@/lib/api/errors';
import type { Secret } from '@/lib/api/types';

import { useCreateSecret, useRotateSecret } from '../api/secrets';

const MAX_VALUE = 16384;

/**
 * 凭据值的输入框：密码框、非受控，值只在提交时读一次；提交后（成功或失败）立即清空。不提供显示明文的开关，也不让浏览器
 * 记住或自动填充（ADR-0056）。
 */
function SecretValueInput({ id, error }: { id: string; error: string | null }) {
  return (
    <Input
      id={id}
      name="secret-value"
      type="password"
      autoComplete="off"
      spellCheck={false}
      maxLength={MAX_VALUE}
      data-1p-ignore
      data-lpignore="true"
      className="font-mono"
      {...fieldAria(id, error)}
    />
  );
}

/** 读出并清空凭据值：DOM 中不留明文。 */
function takeValue(form: HTMLFormElement): string {
  const input = form.elements.namedItem('secret-value') as HTMLInputElement | null;
  const value = input?.value ?? '';
  if (input) input.value = '';
  return value;
}

export type SecretScope = 'tenant' | 'project';

export interface CreateSecretDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** 默认的范围；`lockScope` 时不能改（供应商账号只能引用租户级凭据）。 */
  defaultScope?: SecretScope;
  lockScope?: boolean;
  /** 写入成功后（例如在选择器里直接选中它）。 */
  onCreated?: (secret: Secret) => void;
}

/**
 * 写入凭据：名称、范围（租户内通用或只给当前项目）、凭据值。值输入后立即提交，之后任何地方都看不到明文；名称冲突就地标在
 * 名称上，其余失败带请求 ID 显示在表单里。
 */
export function CreateSecretDialog({
  open,
  onOpenChange,
  defaultScope = 'tenant',
  lockScope = false,
  onCreated,
}: CreateSecretDialogProps) {
  const { projectId, projectName } = useProject();
  const create = useCreateSecret();
  const uid = useId();
  const nameRef = useRef<HTMLInputElement>(null);
  const [scope, setScope] = useState<SecretScope>(defaultScope);
  const [nameError, setNameError] = useState<string | null>(null);
  const [valueError, setValueError] = useState<string | null>(null);

  const close = (next: boolean) => {
    if (create.pending) return;
    if (!next) {
      setNameError(null);
      setValueError(null);
      setScope(defaultScope);
      create.reset();
    }
    onOpenChange(next);
  };

  const submit = async (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault();
    // 对话框可能开在另一个表单里（供应商账号的凭据选择器）：提交事件沿 React 树冒泡，不能触发外层表单。
    event.stopPropagation();
    const form = event.currentTarget;
    const name = (nameRef.current?.value ?? '').trim();
    const value = takeValue(form);
    const missingName = name ? null : '写一个名称，用来在供应商账号与 MCP 服务里选择它。';
    const missingValue = value ? null : '输入凭据值。';
    setNameError(missingName);
    setValueError(missingValue);
    if (missingName || missingValue) return;
    try {
      const secret = await create.run({ name, projectId: scope === 'project' ? projectId : null, value });
      close(false);
      onCreated?.(secret);
    } catch (error) {
      if (error instanceof ApiError && error.problem?.code === 'secret.name_taken')
        setNameError('同一范围内已有同名的凭据，换一个名字。');
      // 其余错误由 create.error 显示；值已清空，重新输入后再提交。
    }
  };

  const otherError =
    create.error && !(create.error instanceof ApiError && create.error.problem?.code === 'secret.name_taken')
      ? create.error
      : null;

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent
        size="md"
        onOpenAutoFocus={(e) => {
          e.preventDefault();
          nameRef.current?.focus();
        }}
      >
        <DialogHeader>
          <DialogTitle>写入凭据</DialogTitle>
          <DialogDescription>保存后只能轮换或删除，任何人都看不到凭据值。</DialogDescription>
        </DialogHeader>
        <form id={`${uid}-form`} className="flex flex-col gap-4" onSubmit={(e) => void submit(e)}>
          <FormField id={`${uid}-name`} label="名称" error={nameError}>
            <Input
              ref={nameRef}
              id={`${uid}-name`}
              maxLength={128}
              placeholder="DashScope 生产"
              autoComplete="off"
              {...fieldAria(`${uid}-name`, nameError)}
            />
          </FormField>
          <FormField
            id={`${uid}-scope`}
            label="范围"
            group
            hint="供应商账号与租户设置只能引用租户级凭据；只给当前项目的凭据只有这个项目的 MCP 服务能引用"
          >
            <RadioGroup
              value={scope}
              onValueChange={(v) => setScope(v as SecretScope)}
              disabled={lockScope}
              aria-labelledby={`${uid}-scope-label`}
              className="gap-2"
            >
              <div className="flex items-center gap-2">
                <RadioGroupItem id={`${uid}-tenant`} value="tenant" />
                <Label htmlFor={`${uid}-tenant`} className="font-normal">
                  租户内通用
                </Label>
              </div>
              <div className="flex items-center gap-2">
                <RadioGroupItem id={`${uid}-project`} value="project" />
                <Label htmlFor={`${uid}-project`} className="font-normal">
                  只给{projectName}
                </Label>
              </div>
            </RadioGroup>
          </FormField>
          <FormField
            id={`${uid}-value`}
            label="凭据值"
            hint="API Key、令牌等；用作 MCP 请求头时写请求头的完整取值，例如 Bearer 加令牌"
            error={valueError}
          >
            <SecretValueInput id={`${uid}-value`} error={valueError} />
          </FormField>
          {otherError ? (
            <ErrorState size="inline" title="写入失败，请重新输入凭据值" error={otherError} />
          ) : null}
        </form>
        <DialogFooter>
          <Button disabled={create.pending} onClick={() => close(false)}>
            取消
          </Button>
          <Button type="submit" form={`${uid}-form`} variant="primary" pending={create.pending}>
            保存凭据
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export interface RotateSecretDialogProps {
  secret: Secret | null;
  onOpenChange: (open: boolean) => void;
}

/** 轮换凭据：写入新值，版本号加一；旧值立即失效、不能恢复，引用它的资源随即改用新值。 */
export function RotateSecretDialog({ secret, onOpenChange }: RotateSecretDialogProps) {
  const rotate = useRotateSecret();
  const uid = useId();
  const [valueError, setValueError] = useState<string | null>(null);

  const close = (next: boolean) => {
    if (rotate.pending) return;
    if (!next) {
      setValueError(null);
      rotate.reset();
    }
    onOpenChange(next);
  };

  const submit = async (event: SubmitEvent<HTMLFormElement>) => {
    event.preventDefault();
    // 对话框可能开在另一个表单里（供应商账号的凭据选择器）：提交事件沿 React 树冒泡，不能触发外层表单。
    event.stopPropagation();
    if (!secret) return;
    const value = takeValue(event.currentTarget);
    if (!value) return setValueError('输入新的凭据值。');
    setValueError(null);
    try {
      await rotate.run({ id: secret.id, value });
      close(false);
    } catch {
      // 错误由 rotate.error 显示。
    }
  };

  const users = secret?.references.length ?? 0;
  return (
    <Dialog open={secret !== null} onOpenChange={close}>
      <DialogContent
        size="md"
        onOpenAutoFocus={(e) => {
          e.preventDefault();
          document.getElementById(`${uid}-value`)?.focus();
        }}
      >
        <DialogHeader>
          <DialogTitle>轮换 {secret?.name}</DialogTitle>
          <DialogDescription>
            {users > 0
              ? `旧值立即失效、不能恢复；引用它的 ${users} 个资源随即改用新值。`
              : '旧值立即失效、不能恢复。'}
          </DialogDescription>
        </DialogHeader>
        <form id={`${uid}-form`} className="flex flex-col gap-4" onSubmit={(e) => void submit(e)}>
          <FormField id={`${uid}-value`} label="新的凭据值" error={valueError}>
            <SecretValueInput id={`${uid}-value`} error={valueError} />
          </FormField>
          {rotate.error ? (
            <ErrorState size="inline" title="轮换失败，请重新输入凭据值" error={rotate.error} />
          ) : null}
        </form>
        <DialogFooter>
          <Button disabled={rotate.pending} onClick={() => close(false)}>
            取消
          </Button>
          <Button type="submit" form={`${uid}-form`} variant="primary" pending={rotate.pending}>
            {secret ? `轮换为版本 ${secret.version + 1}` : '轮换'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
