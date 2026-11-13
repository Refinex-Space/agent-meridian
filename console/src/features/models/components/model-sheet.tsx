import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';

import { ErrorState } from '@/components/domain/error-state';
import { FormField, fieldAria } from '@/components/domain/form-field';
import { ResourceSelect, type ResourceSource } from '@/components/domain/resource-picker';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import {
  Sheet,
  SheetBody,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet';
import { useProject } from '@/lib/access';
import { ApiError } from '@/lib/api/errors';
import type { CatalogModel, ModelCapabilities, ModelKind } from '@/lib/api/types';
import { formatTokens } from '@/lib/format';

import {
  catalogModelsQuery,
  priceBooksQuery,
  providerAccountsQuery,
  useCreateModel,
  useUpdateModel,
} from '../api/queries';
import {
  CAPABILITY_LABEL,
  MODEL_KIND_LABEL,
  parsePositive,
  positiveText,
  PROVIDER_LABEL,
} from '../model/catalog';

export interface ModelSheetProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** 修改已有模型（供应商账号与种类不能改）；不给时是新建。 */
  model?: CatalogModel;
}

interface Form {
  providerAccountId: string | null;
  name: string;
  kind: ModelKind;
  upstream: string;
  contextWindow: string;
  maxOutput: string;
  capabilities: ModelCapabilities;
  fallbackId: string | null;
  priceBookId: string | null;
  baseUrl: string;
  maxBatch: string;
  rpm: string;
  tpm: string;
}

const KINDS: readonly ModelKind[] = ['chat', 'embedding', 'rerank'];
const NO_CAPABILITIES: ModelCapabilities = { tool_calls: false, vision: false, reasoning: false };

function initial(model?: CatalogModel): Form {
  return {
    providerAccountId: model?.provider_account_id ?? null,
    name: model?.name ?? '',
    kind: model?.kind ?? 'chat',
    upstream: model?.upstream_model ?? '',
    contextWindow: positiveText(model?.context_window),
    maxOutput: positiveText(model?.max_output_tokens),
    capabilities: model?.capabilities ?? { ...NO_CAPABILITIES, tool_calls: true },
    fallbackId: model?.fallback_model_id ?? null,
    priceBookId: model?.price_book_id ?? null,
    baseUrl: model?.base_url ?? '',
    maxBatch: positiveText(model?.max_batch_size),
    rpm: positiveText(model?.rate_limit.rpm),
    tpm: positiveText(model?.rate_limit.tpm),
  };
}

function validate(form: Form): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!form.providerAccountId) errors['provider_account_id'] = '选择供应商账号。';
  if (!form.name.trim()) errors['name'] = '写一个名称，智能体按它选择模型。';
  if (!form.upstream.trim()) errors['upstream_model'] = '写供应商侧的模型名，例如 qwen-plus。';
  const required: [keyof Form, string][] = [
    ['contextWindow', 'context_window'],
    ['maxOutput', 'max_output_tokens'],
  ];
  for (const [key, field] of required) {
    const value = parsePositive(form[key] as string);
    if (value === null || Number.isNaN(value)) errors[field] = '写正整数。';
  }
  const optional: [keyof Form, string][] = [
    ['maxBatch', 'max_batch_size'],
    ['rpm', 'rate_limit.rpm'],
    ['tpm', 'rate_limit.tpm'],
  ];
  for (const [key, field] of optional)
    if (Number.isNaN(parsePositive(form[key] as string))) errors[field] = '写正整数，留空表示用默认值。';
  const url = form.baseUrl.trim();
  if (url && !/^https?:\/\/\S+$/.test(url)) errors['base_url'] = '写 http 或 https 开头的地址。';
  return errors;
}

function mapServerErrors(error: unknown): Record<string, string> | null {
  if (!(error instanceof ApiError)) return null;
  if (error.problem?.code === 'catalog.name_taken') return { name: '租户内已有同名的模型，换一个名字。' };
  const violations = error.problem?.errors ?? [];
  return violations.length > 0 ? Object.fromEntries(violations.map((v) => [v.field, v.message])) : null;
}

const FIELDS = new Set([
  'provider_account_id',
  'name',
  'upstream_model',
  'context_window',
  'max_output_tokens',
  'fallback_model_id',
  'price_book_id',
  'base_url',
  'max_batch_size',
  'rate_limit.rpm',
  'rate_limit.tpm',
  'kind',
]);

/** 数字字段：正整数，右对齐等宽数字。 */
function NumberField({
  id,
  label,
  hint,
  value,
  placeholder,
  error,
  onChange,
}: {
  id: string;
  label: string;
  hint?: string;
  value: string;
  placeholder?: string;
  error?: string;
  onChange: (value: string) => void;
}) {
  return (
    <FormField id={id} label={label} hint={hint} error={error}>
      <Input
        id={id}
        inputMode="numeric"
        value={value}
        placeholder={placeholder}
        className="tabular-nums"
        onChange={(e) => onChange(e.target.value)}
        {...fieldAria(id, error)}
      />
    </FormField>
  );
}

/**
 * 新建或修改模型（抽屉）：供应商账号、种类、上游模型名、上下文窗口与默认最大输出、能力、备用模型、价格表；端点、批量与限流是
 * 可选的覆盖。保存后 runtime 立即改用新配置。
 */
export function ModelSheet({ open, onOpenChange, model }: ModelSheetProps) {
  const { tenantId } = useProject();
  const accounts = useQuery({ ...providerAccountsQuery(tenantId), enabled: open });
  const models = useQuery({ ...catalogModelsQuery(tenantId), enabled: open });
  const priceBooks = useQuery({ ...priceBooksQuery(tenantId), enabled: open });
  const create = useCreateModel();
  const update = useUpdateModel();
  const mutation = model ? update : create;
  const [form, setForm] = useState<Form>(() => initial(model));
  const [submitted, setSubmitted] = useState(false);
  const [server, setServer] = useState<{ errors: Record<string, string>; snapshot: string } | null>(null);

  const local = submitted ? validate(form) : {};
  const fromServer = server && server.snapshot === JSON.stringify(form) ? server.errors : {};
  const errors: Record<string, string> = { ...fromServer, ...local };
  const onFields = server !== null && Object.keys(server.errors).every((k) => FIELDS.has(k));
  const rootError = mutation.error && !onFields ? mutation.error : null;
  const patch = (next: Partial<Form>) => setForm((f) => ({ ...f, ...next }));
  const account = accounts.data?.find((a) => a.id === form.providerAccountId);
  // Embedding 与重排只能挂在 OpenAI 兼容的账号下。
  const kinds = account && account.provider !== 'openai_compatible' ? (['chat'] as const) : KINDS;

  const close = (next: boolean) => {
    if (mutation.isPending) return;
    if (!next) {
      setForm(initial(model));
      setSubmitted(false);
      setServer(null);
      create.reset();
      update.reset();
    }
    onOpenChange(next);
  };

  const accountSource: ResourceSource = {
    options: (accounts.data ?? []).map((a) => ({
      id: a.id,
      name: a.name,
      detail: PROVIDER_LABEL[a.provider],
    })),
    loading: accounts.isPending,
    error: accounts.error,
    onRetry: () => void accounts.refetch(),
  };
  const fallbackSource: ResourceSource = {
    options: (models.data ?? [])
      .filter((m) => m.kind === form.kind && m.id !== model?.id)
      .map((m) => ({
        id: m.id,
        name: m.name,
        mono: true,
        detail: `上下文 ${formatTokens(m.context_window)}`,
      })),
    loading: models.isPending,
    error: models.error,
    onRetry: () => void models.refetch(),
  };
  const priceSource: ResourceSource = {
    options: (priceBooks.data ?? []).map((p) => ({
      id: p.id,
      name: p.name,
      detail: `v${p.current_version.version}，${p.currency}`,
    })),
    loading: priceBooks.isPending,
    error: priceBooks.error,
    onRetry: () => void priceBooks.refetch(),
  };

  const submit = () => {
    setSubmitted(true);
    setServer(null);
    if (Object.keys(validate(form)).length > 0) return;
    const body = {
      name: form.name.trim(),
      upstream_model: form.upstream.trim(),
      context_window: parsePositive(form.contextWindow)!,
      max_output_tokens: parsePositive(form.maxOutput)!,
      capabilities: form.kind === 'chat' ? form.capabilities : NO_CAPABILITIES,
      fallback_model_id: form.fallbackId,
      price_book_id: form.priceBookId,
      base_url: form.baseUrl.trim() || null,
      max_batch_size: form.kind === 'embedding' ? parsePositive(form.maxBatch) : null,
      rate_limit: { rpm: parsePositive(form.rpm), tpm: parsePositive(form.tpm) },
    };
    const onError = (error: unknown) => {
      const mapped = mapServerErrors(error);
      if (mapped) setServer({ errors: mapped, snapshot: JSON.stringify(form) });
    };
    if (model) update.mutate({ id: model.id, body }, { onSuccess: () => close(false), onError });
    else
      create.mutate(
        { ...body, provider_account_id: form.providerAccountId!, kind: form.kind },
        { onSuccess: () => close(false), onError },
      );
  };

  return (
    <Sheet open={open} onOpenChange={close}>
      <SheetContent size="lg">
        <SheetHeader>
          <SheetTitle>{model ? `修改 ${model.name}` : '添加模型'}</SheetTitle>
          <SheetDescription>
            {model ? '保存后 runtime 立即改用新配置。' : '模型属于租户，全部项目的智能体都可以选用。'}
          </SheetDescription>
        </SheetHeader>
        <SheetBody label="模型的配置">
          <form
            id="model-form"
            className="flex flex-col gap-6"
            onSubmit={(e) => {
              e.preventDefault();
              submit();
            }}
          >
            <div className="flex flex-col gap-4">
              <FormField
                id="model-account"
                label="供应商账号"
                hint="创建后不能改"
                error={errors['provider_account_id']}
              >
                <ResourceSelect
                  id="model-account"
                  value={form.providerAccountId}
                  onChange={(id) =>
                    setForm((f) => {
                      const next = accounts.data?.find((a) => a.id === id);
                      const chatOnly = next && next.provider !== 'openai_compatible';
                      return { ...f, providerAccountId: id, kind: chatOnly ? 'chat' : f.kind };
                    })
                  }
                  source={accountSource}
                  searchLabel="搜索供应商账号"
                  emptyLabel="还没有供应商账号：先在供应商账号里新建"
                  disabled={Boolean(model)}
                  invalid={Boolean(errors['provider_account_id'])}
                  describedBy={errors['provider_account_id'] ? 'model-account-error' : undefined}
                />
              </FormField>
              <FormField
                id="model-kind"
                label="种类"
                group
                hint="Embedding 与重排只能挂在 OpenAI 兼容的账号下；创建后不能改"
              >
                <RadioGroup
                  value={form.kind}
                  disabled={Boolean(model)}
                  onValueChange={(v) => patch({ kind: v as ModelKind, fallbackId: null })}
                  aria-labelledby="model-kind-label"
                  className="flex gap-6"
                >
                  {kinds.map((k) => (
                    <div key={k} className="flex items-center gap-2">
                      <RadioGroupItem id={`model-kind-${k}`} value={k} />
                      <Label htmlFor={`model-kind-${k}`} className="font-normal">
                        {MODEL_KIND_LABEL[k]}
                      </Label>
                    </div>
                  ))}
                </RadioGroup>
              </FormField>
              <div className="grid grid-cols-2 gap-4">
                <FormField
                  id="model-name"
                  label="名称"
                  hint="租户内唯一；智能体与直通接口按模型 ID 引用"
                  error={errors['name']}
                >
                  <Input
                    id="model-name"
                    value={form.name}
                    maxLength={128}
                    placeholder="qwen-plus"
                    autoComplete="off"
                    onChange={(e) => patch({ name: e.target.value })}
                    {...fieldAria('model-name', errors['name'])}
                  />
                </FormField>
                <FormField
                  id="model-upstream"
                  label="上游模型名"
                  hint="供应商侧的模型名"
                  error={errors['upstream_model']}
                >
                  <Input
                    id="model-upstream"
                    value={form.upstream}
                    maxLength={256}
                    placeholder="qwen-plus"
                    autoComplete="off"
                    className="font-mono"
                    onChange={(e) => patch({ upstream: e.target.value })}
                    {...fieldAria('model-upstream', errors['upstream_model'])}
                  />
                </FormField>
              </div>
            </div>

            <div className="flex flex-col gap-4">
              <h3 className="text-13 font-semibold">参数</h3>
              <div className="grid grid-cols-2 gap-4">
                <NumberField
                  id="model-context"
                  label="上下文窗口"
                  hint="单位 token；上下文管理按它决定何时压缩"
                  value={form.contextWindow}
                  placeholder="131072"
                  error={errors['context_window']}
                  onChange={(contextWindow) => patch({ contextWindow })}
                />
                <NumberField
                  id="model-max-output"
                  label="默认最大输出"
                  hint="请求没有指定 max_tokens 时的最大输出 token"
                  value={form.maxOutput}
                  placeholder="2048"
                  error={errors['max_output_tokens']}
                  onChange={(maxOutput) => patch({ maxOutput })}
                />
              </div>
              {form.kind === 'chat' && (
                <FormField id="model-capabilities" label="能力" group>
                  <div role="group" aria-labelledby="model-capabilities-label" className="flex gap-6">
                    {(Object.keys(CAPABILITY_LABEL) as (keyof ModelCapabilities)[]).map((key) => (
                      <div key={key} className="flex items-center gap-2">
                        <Checkbox
                          id={`model-cap-${key}`}
                          checked={form.capabilities[key]}
                          onCheckedChange={(checked) =>
                            patch({ capabilities: { ...form.capabilities, [key]: checked === true } })
                          }
                        />
                        <Label htmlFor={`model-cap-${key}`} className="font-normal">
                          {CAPABILITY_LABEL[key]}
                        </Label>
                      </div>
                    ))}
                  </div>
                </FormField>
              )}
              {form.kind === 'embedding' && (
                <NumberField
                  id="model-batch"
                  label="一次请求的条数上限"
                  hint="Embedding 一次请求的输入条数；留空为 10"
                  value={form.maxBatch}
                  placeholder="10"
                  error={errors['max_batch_size']}
                  onChange={(maxBatch) => patch({ maxBatch })}
                />
              )}
            </div>

            <div className="flex flex-col gap-4">
              <h3 className="text-13 font-semibold">计价与降级</h3>
              <FormField
                id="model-price-book"
                label="价格表"
                hint="用量按价格表的当前版本计价；不选时只记 token、不计成本"
                error={errors['price_book_id']}
              >
                <ResourceSelect
                  id="model-price-book"
                  value={form.priceBookId}
                  onChange={(priceBookId) => patch({ priceBookId })}
                  source={priceSource}
                  nullOption="不计价"
                  searchLabel="搜索价格表"
                  emptyLabel="还没有价格表"
                />
              </FormField>
              <FormField
                id="model-fallback"
                label="备用模型"
                hint="供应商出错（连不上、5xx、429）重试 2 次仍失败时改用它；预算与限流不切换。必须是同种类的另一个模型"
                error={errors['fallback_model_id']}
              >
                <ResourceSelect
                  id="model-fallback"
                  value={form.fallbackId}
                  onChange={(fallbackId) => patch({ fallbackId })}
                  source={fallbackSource}
                  nullOption="没有备用模型"
                  searchLabel="搜索模型"
                  emptyLabel="没有同种类的其他模型"
                />
              </FormField>
            </div>

            <div className="flex flex-col gap-4">
              <h3 className="text-13 font-semibold">覆盖账号的设置</h3>
              <FormField
                id="model-base-url"
                label="端点"
                hint="留空用账号的端点；例如 DashScope 的重排在 compatible-api/v1"
                error={errors['base_url']}
              >
                <Input
                  id="model-base-url"
                  value={form.baseUrl}
                  placeholder="使用账号的端点"
                  autoComplete="off"
                  className="font-mono"
                  onChange={(e) => patch({ baseUrl: e.target.value })}
                  {...fieldAria('model-base-url', errors['base_url'])}
                />
              </FormField>
              <div className="grid grid-cols-2 gap-4">
                <NumberField
                  id="model-rpm"
                  label="每分钟请求上限"
                  hint="留空用账号的上限"
                  value={form.rpm}
                  placeholder="用账号的"
                  error={errors['rate_limit.rpm']}
                  onChange={(rpm) => patch({ rpm })}
                />
                <NumberField
                  id="model-tpm"
                  label="每分钟 token 上限"
                  value={form.tpm}
                  placeholder="用账号的"
                  error={errors['rate_limit.tpm']}
                  onChange={(tpm) => patch({ tpm })}
                />
              </div>
            </div>
            {rootError ? <ErrorState size="inline" title="保存失败" error={rootError} /> : null}
          </form>
        </SheetBody>
        <SheetFooter>
          <Button disabled={mutation.isPending} onClick={() => close(false)}>
            取消
          </Button>
          <Button type="submit" form="model-form" variant="primary" pending={mutation.isPending}>
            {model ? '保存' : '添加模型'}
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  );
}
