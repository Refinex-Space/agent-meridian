import { useState } from 'react';

import { ErrorState } from '@/components/domain/error-state';
import { FormField, fieldAria } from '@/components/domain/form-field';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { SecretSelect } from '@/features/settings';
import { ApiError } from '@/lib/api/errors';
import type { ProviderAccount, ProviderType } from '@/lib/api/types';

import { useCreateProviderAccount, useUpdateProviderAccount } from '../api/queries';
import { ENDPOINT_PLACEHOLDER, parsePositive, positiveText, PROVIDER_LABEL } from '../model/catalog';

export interface ProviderDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** 修改已有账号（类型不能改）；不给时是新建。 */
  account?: ProviderAccount;
  /** 新建成功后（列表里就地出现，可直接测试连通性）。 */
  onCreated?: (account: ProviderAccount) => void;
}

interface Form {
  name: string;
  provider: ProviderType;
  baseUrl: string;
  secretId: string | null;
  rpm: string;
  tpm: string;
}

const PROVIDERS: readonly ProviderType[] = ['openai_compatible', 'anthropic', 'gemini'];

function initial(account?: ProviderAccount): Form {
  return {
    name: account?.name ?? '',
    provider: account?.provider ?? 'openai_compatible',
    baseUrl: account?.base_url ?? '',
    secretId: account?.secret_id ?? null,
    rpm: positiveText(account?.rate_limit.rpm),
    tpm: positiveText(account?.rate_limit.tpm),
  };
}

function validate(form: Form): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!form.name.trim()) errors['name'] = '写一个名称，模型目录里按它选择账号。';
  const url = form.baseUrl.trim();
  if (form.provider === 'openai_compatible' && !url) errors['base_url'] = 'OpenAI 兼容的账号要写端点。';
  else if (url && !/^https?:\/\/\S+$/.test(url)) errors['base_url'] = '写 http 或 https 开头的地址。';
  for (const key of ['rpm', 'tpm'] as const)
    if (Number.isNaN(parsePositive(form[key]))) errors[`rate_limit.${key}`] = '写正整数，留空表示不限。';
  return errors;
}

function mapServerErrors(error: unknown): Record<string, string> | null {
  if (!(error instanceof ApiError)) return null;
  if (error.problem?.code === 'catalog.name_taken')
    return { name: '租户内已有同名的供应商账号，换一个名字。' };
  const violations = error.problem?.errors ?? [];
  return violations.length > 0 ? Object.fromEntries(violations.map((v) => [v.field, v.message])) : null;
}

const KNOWN_FIELDS = new Set(['name', 'base_url', 'secret_id', 'rate_limit.rpm', 'rate_limit.tpm']);

/**
 * 新建或修改供应商账号：类型、端点、凭据（只能选租户级凭据，可以就地写入一个新的）与限流。保存不会调用供应商；保存后在列表里
 * 测试连通性。
 */
export function ProviderDialog({ open, onOpenChange, account, onCreated }: ProviderDialogProps) {
  const create = useCreateProviderAccount();
  const update = useUpdateProviderAccount();
  const mutation = account ? update : create;
  const [form, setForm] = useState<Form>(() => initial(account));
  const [submitted, setSubmitted] = useState(false);
  const [server, setServer] = useState<{ errors: Record<string, string>; snapshot: string } | null>(null);

  const local = submitted ? validate(form) : {};
  const fromServer = server && server.snapshot === JSON.stringify(form) ? server.errors : {};
  const errors: Record<string, string> = { ...fromServer, ...local };
  const onFields = server !== null && Object.keys(server.errors).every((k) => KNOWN_FIELDS.has(k));
  const rootError = mutation.error && !onFields ? mutation.error : null;
  const patch = (next: Partial<Form>) => setForm((f) => ({ ...f, ...next }));

  const close = (next: boolean) => {
    if (mutation.isPending) return;
    if (!next) {
      setForm(initial(account));
      setSubmitted(false);
      setServer(null);
      create.reset();
      update.reset();
    }
    onOpenChange(next);
  };

  const submit = () => {
    setSubmitted(true);
    setServer(null);
    if (Object.keys(validate(form)).length > 0) return;
    const common = {
      name: form.name.trim(),
      base_url: form.baseUrl.trim() || null,
      secret_id: form.secretId,
      rate_limit: { rpm: parsePositive(form.rpm), tpm: parsePositive(form.tpm) },
    };
    const onError = (error: unknown) => {
      const mapped = mapServerErrors(error);
      if (mapped) setServer({ errors: mapped, snapshot: JSON.stringify(form) });
    };
    if (account) update.mutate({ id: account.id, body: common }, { onSuccess: () => close(false), onError });
    else
      create.mutate(
        { ...common, provider: form.provider },
        {
          onSuccess: (created) => {
            close(false);
            onCreated?.(created);
          },
          onError,
        },
      );
  };

  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent
        size="lg"
        onOpenAutoFocus={(e) => {
          e.preventDefault();
          document.getElementById('provider-name')?.focus();
        }}
      >
        <DialogHeader>
          <DialogTitle>{account ? `修改 ${account.name}` : '新建供应商账号'}</DialogTitle>
          <DialogDescription>
            {account
              ? '保存后 runtime 立即改用新配置，模型目录中挂在这个账号下的模型随之生效。'
              : '账号是端点加凭据；之后在模型目录里给它添加模型。'}
          </DialogDescription>
        </DialogHeader>
        <DialogBody label="供应商账号的配置">
          <form
            id="provider-form"
            className="flex flex-col gap-4"
            onSubmit={(e) => {
              e.preventDefault();
              submit();
            }}
          >
            <FormField id="provider-name" label="名称" hint="租户内唯一" error={errors['name']}>
              <Input
                id="provider-name"
                value={form.name}
                maxLength={128}
                placeholder="DashScope"
                autoComplete="off"
                className="max-w-100"
                onChange={(e) => patch({ name: e.target.value })}
                {...fieldAria('provider-name', errors['name'])}
              />
            </FormField>
            <FormField
              id="provider-type"
              label="类型"
              group
              hint="DashScope 兼容模式、vLLM、SGLang 等私有化推理服务都选 OpenAI 兼容；创建后不能改"
            >
              <RadioGroup
                value={form.provider}
                disabled={Boolean(account)}
                onValueChange={(v) => patch({ provider: v as ProviderType })}
                aria-labelledby="provider-type-label"
                className="flex gap-6"
              >
                {PROVIDERS.map((p) => (
                  <div key={p} className="flex items-center gap-2">
                    <RadioGroupItem id={`provider-type-${p}`} value={p} />
                    <Label htmlFor={`provider-type-${p}`} className="font-normal">
                      {PROVIDER_LABEL[p]}
                    </Label>
                  </div>
                ))}
              </RadioGroup>
            </FormField>
            <FormField
              id="provider-base-url"
              label="端点"
              hint="http 或 https；链路本地、云元数据与保留地址一律拒绝，内网地址只在平台放行的网段内可用"
              error={errors['base_url']}
            >
              <Input
                id="provider-base-url"
                value={form.baseUrl}
                placeholder={ENDPOINT_PLACEHOLDER[form.provider]}
                autoComplete="off"
                className="font-mono"
                onChange={(e) => patch({ baseUrl: e.target.value })}
                {...fieldAria('provider-base-url', errors['base_url'])}
              />
            </FormField>
            <FormField
              id="provider-secret"
              label="凭据"
              hint="供应商的 API Key；只能选租户级凭据"
              error={errors['secret_id']}
            >
              <SecretSelect
                id="provider-secret"
                value={form.secretId}
                onChange={(secretId) => patch({ secretId })}
                usedBy="tenant"
                nullOption="不需要凭据"
                invalid={Boolean(errors['secret_id'])}
                describedBy={errors['secret_id'] ? 'provider-secret-error' : undefined}
              />
            </FormField>
            <div className="grid grid-cols-2 gap-4">
              <FormField
                id="provider-rpm"
                label="每分钟请求上限"
                hint="账号下全部模型共用；模型可以另设更严的上限。留空表示不限"
                error={errors['rate_limit.rpm']}
              >
                <Input
                  id="provider-rpm"
                  inputMode="numeric"
                  value={form.rpm}
                  placeholder="不限"
                  className="tabular-nums"
                  onChange={(e) => patch({ rpm: e.target.value })}
                  {...fieldAria('provider-rpm', errors['rate_limit.rpm'])}
                />
              </FormField>
              <FormField id="provider-tpm" label="每分钟 token 上限" error={errors['rate_limit.tpm']}>
                <Input
                  id="provider-tpm"
                  inputMode="numeric"
                  value={form.tpm}
                  placeholder="不限"
                  className="tabular-nums"
                  onChange={(e) => patch({ tpm: e.target.value })}
                  {...fieldAria('provider-tpm', errors['rate_limit.tpm'])}
                />
              </FormField>
            </div>
            {rootError ? <ErrorState size="inline" title="保存失败" error={rootError} /> : null}
          </form>
        </DialogBody>
        <DialogFooter>
          <Button disabled={mutation.isPending} onClick={() => close(false)}>
            取消
          </Button>
          <Button type="submit" form="provider-form" variant="primary" pending={mutation.isPending}>
            {account ? '保存' : '创建账号'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
