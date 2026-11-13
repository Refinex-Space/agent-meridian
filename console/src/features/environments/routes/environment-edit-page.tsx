import { useSuspenseQuery } from '@tanstack/react-query';
import { useNavigate } from '@tanstack/react-router';
import { useMemo, useState, type FocusEvent } from 'react';
import { Controller, useForm } from 'react-hook-form';

import { CopyableId } from '@/components/domain/copyable-id';
import { DangerConfirmDialog } from '@/components/domain/danger-confirm-dialog';
import { DataRegion } from '@/components/domain/data-region';
import { ErrorState } from '@/components/domain/error-state';
import { FormField, fieldAria } from '@/components/domain/form-field';
import { LeaveGuard } from '@/components/domain/leave-guard';
import { DetailPage, EditPage, EditSection } from '@/components/templates';
import { PermissionGate } from '@/components/domain/permission-gate';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { DotsThreeIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Skeleton } from '@/components/ui/skeleton';
import { meQuery, usePermission, useProject } from '@/lib/access';
import { ApiError } from '@/lib/api/errors';
import type { Environment } from '@/lib/api/types';
import { cn } from '@/lib/cn';
import { useCrumb } from '@/lib/crumbs';

import {
  environmentQuery,
  useCreateEnvironment,
  useDeleteEnvironment,
  useUpdateEnvironment,
} from '../api/queries';
import { AllowlistEditor } from '../components/allowlist-editor';
import { RevisionHistory } from '../components/revision-history';
import {
  changesRevision,
  environmentSchema,
  SIZES,
  toForm,
  toRequest,
  type EnvironmentForm,
} from '../model/environment';

export interface EnvironmentEditPageProps {
  /** 编辑的环境；新建时为空。 */
  environmentId?: string;
}

const TIERS = [
  ['none', '无沙箱', '只有文件工具，文件存在平台的工作区里；不能执行命令'],
  ['sandbox', '沙箱', '每个会话一个隔离的沙箱，可以执行命令；按资源规格计量'],
] as const;

/** 名字冲突没有字段明细，按错误码放到名字上。 */
const NAME_TAKEN = 'environment.name_taken';

type Errors = Record<string, string>;

/** 校验结果：键是字段（`name`、`size`、`egress_allowlist`）或白名单的一条（`egress_allowlist.N`）。 */
function validate(form: EnvironmentForm): Errors {
  const errors: Errors = {};
  const result = environmentSchema.safeParse(form);
  if (!result.success)
    for (const issue of result.error.issues) {
      const key = issue.path.map(String).join('.');
      errors[key] ??= issue.message;
    }
  return errors;
}

/** 服务端的字段错误：`name`、`tier`、`size`、`egress_allowlist`、`egress_allowlist.N`。 */
function serverErrors(error: unknown): Errors | null {
  if (!(error instanceof ApiError)) return null;
  if (error.problem?.code === NAME_TAKEN) return { name: '项目内已有同名的环境，换一个名字。' };
  const violations = error.problem?.errors ?? [];
  if (violations.length === 0) return null;
  return Object.fromEntries(violations.map((v) => [v.field, v.message]));
}

const FIELD_GROUP: Record<string, string> = { name: 'env-basic', tier: 'env-tier', size: 'env-tier' };
const groupOf = (key: string) => FIELD_GROUP[key] ?? 'env-egress';

/** 字段的控件 id。 */
const FIELD_ID: Record<string, string> = { name: 'env-name', tier: 'tier-none', size: 'size-small' };
const idOf = (key: string) => FIELD_ID[key] ?? 'env-domain';

function Editor({ environment }: { environment: Environment | null }) {
  const { projectId } = useProject();
  const navigate = useNavigate();
  const write = usePermission('agent_write', '修改环境');
  const me = useSuspenseQuery(meQuery()).data.subject;
  const initial = useMemo(() => toForm(environment), [environment]);
  const form = useForm<EnvironmentForm>({ defaultValues: initial });
  // 每次输入都重算校验：订阅整个表单（表单很小）。本项目不启用 React Compiler。
  // eslint-disable-next-line react-hooks/incompatible-library
  const values = form.watch();
  const create = useCreateEnvironment();
  const update = useUpdateEnvironment(environment?.id ?? '');
  const remove = useDeleteEnvironment();
  const [submitted, setSubmitted] = useState(false);
  const [touched, setTouched] = useState<ReadonlySet<string>>(new Set());
  const [server, setServer] = useState<{ errors: Errors; snapshot: string } | null>(null);
  const [rootError, setRootError] = useState<unknown>(null);
  const [deleting, setDeleting] = useState(false);
  useCrumb(environment?.name);

  const readOnly = !write.allowed;
  const dirty = form.formState.isDirty;
  const local = validate(values);
  // 服务端的错误在表单再次改动之前一直显示。
  const fromServer = server && server.snapshot === JSON.stringify(values) ? server.errors : {};
  const errors: Errors = { ...fromServer };
  for (const [key, message] of Object.entries(local))
    if (submitted || touched.has(key.split('.')[0]!)) errors[key] = message;
  const itemErrors: Record<number, string> = {};
  for (const [key, message] of Object.entries(errors)) {
    const match = /^egress_allowlist\.(\d+)$/.exec(key);
    if (match) itemErrors[Number(match[1])] = message;
  }
  const invalidGroups = new Set(Object.keys(errors).map(groupOf));
  const newRevision = environment !== null && changesRevision(initial, values);
  const busy = create.isPending || update.isPending;

  const focusFirst = (keys: string[]) => {
    const order = ['name', 'tier', 'size'];
    const first = [...keys].sort((a, b) => (order.indexOf(a) + 1 || 9) - (order.indexOf(b) + 1 || 9))[0];
    if (!first) return;
    window.requestAnimationFrame(() => {
      const el = document.getElementById(idOf(first));
      el?.scrollIntoView({ block: 'center' });
      el?.focus({ preventScroll: true });
    });
  };

  const save = async () => {
    setSubmitted(true);
    const current = form.getValues();
    const found = Object.keys(validate(current));
    if (found.length > 0) return focusFirst(found);
    setServer(null);
    setRootError(null);
    try {
      const saved = environment
        ? await update.mutateAsync(toRequest(current))
        : await create.mutateAsync(toRequest(current));
      form.reset(toForm(saved));
      if (!environment)
        void navigate({
          to: '/projects/$projectId/environments/$environmentId',
          params: { projectId, environmentId: saved.id },
          replace: true,
        });
    } catch (error) {
      const mapped = serverErrors(error);
      if (mapped) {
        setServer({ errors: mapped, snapshot: JSON.stringify(current) });
        focusFirst(Object.keys(mapped));
      } else setRootError(error);
    }
  };

  const onBlurCapture = (event: FocusEvent<HTMLElement>) => {
    const key = event.target.id === 'env-name' ? 'name' : null;
    if (key) setTouched((prev) => (prev.has(key) ? prev : new Set(prev).add(key)));
  };

  const groups = [
    { id: 'env-basic', label: '基本信息' },
    { id: 'env-tier', label: '执行档位' },
    ...(values.tier === 'sandbox' ? [{ id: 'env-egress', label: '出网' }] : []),
    ...(environment ? [{ id: 'env-revisions', label: '修订历史' }] : []),
  ].map((g) => ({ ...g, invalid: invalidGroups.has(g.id) }));

  const nameError = errors['name'];
  const sizeError = errors['size'];
  const listError = errors['egress_allowlist'];

  return (
    <>
      <LeaveGuard when={dirty && !busy} />
      <EditPage
        title={environment ? `编辑 ${environment.name}` : '新建环境'}
        meta={
          environment && (
            <>
              <Badge>修订 {environment.revision}</Badge>
              <CopyableId id={environment.id} label="环境 ID" />
            </>
          )
        }
        actions={
          environment &&
          write.allowed && (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button size="icon-md" aria-label="更多操作">
                  <Icon glyph={DotsThreeIcon} />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="min-w-40">
                <DropdownMenuItem variant="danger" onSelect={() => setDeleting(true)}>
                  删除环境
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          )
        }
        groups={groups}
        notice={
          rootError ? (
            <div className="border-t border-rule px-6 py-3">
              <ErrorState size="inline" title="保存失败" error={rootError} />
            </div>
          ) : undefined
        }
        saveBar={{
          dirty,
          saving: busy,
          savedAt: environment?.updated_at ?? null,
          errorCount: Object.keys(errors).length,
          saveLabel: !environment
            ? '创建环境'
            : newRevision
              ? `保存为修订 ${environment.revision + 1}`
              : '保存',
          onSave: () => void save(),
          onDiscard: () => {
            form.reset();
            setSubmitted(false);
            setTouched(new Set());
            setServer(null);
            setRootError(null);
          },
          readOnly: readOnly ? write.reason : undefined,
        }}
      >
        <div className="contents" onBlurCapture={onBlurCapture}>
          <EditSection id="env-basic" title="基本信息">
            <FormField
              id="env-name"
              label="名称"
              hint="项目内唯一，创建会话时按 ID 引用"
              error={nameError}
              className="max-w-100"
            >
              <Input
                id="env-name"
                readOnly={readOnly}
                {...form.register('name')}
                {...fieldAria('env-name', nameError)}
              />
            </FormField>
          </EditSection>

          <EditSection id="env-tier" title="执行档位">
            <Controller
              control={form.control}
              name="tier"
              render={({ field }) => (
                <RadioGroup
                  value={field.value}
                  disabled={readOnly}
                  onValueChange={(v) => {
                    field.onChange(v);
                    // 换到沙箱档时默认选中规格，换回无沙箱档时清空（服务端不接受无沙箱档带规格）。
                    form.setValue('size', v === 'sandbox' ? (form.getValues('size') ?? 'small') : null, {
                      shouldDirty: true,
                    });
                  }}
                  aria-label="执行档位"
                  className="gap-3"
                >
                  {TIERS.map(([value, label, detail]) => (
                    <div key={value} className="flex items-start gap-2">
                      <span className="flex h-5 items-center">
                        <RadioGroupItem id={`tier-${value}`} value={value} />
                      </span>
                      <Label htmlFor={`tier-${value}`} className="flex-col items-start gap-0 font-normal">
                        <span className="font-medium">{label}</span>
                        <span className="text-ink-2">{detail}</span>
                      </Label>
                    </div>
                  ))}
                </RadioGroup>
              )}
            />
            {values.tier === 'sandbox' && (
              <FormField
                id="env-size"
                label="资源规格"
                group
                error={sizeError}
                hint="每种规格对应一套预部署的沙箱模板与预热池"
              >
                <Controller
                  control={form.control}
                  name="size"
                  render={({ field }) => (
                    <RadioGroup
                      value={field.value ?? ''}
                      disabled={readOnly}
                      onValueChange={field.onChange}
                      aria-labelledby="env-size-label"
                      aria-describedby={sizeError ? 'env-size-error' : undefined}
                      className="grid grid-cols-3 gap-0 border-y border-rule"
                    >
                      {SIZES.map((size, i) => (
                        <Label
                          key={size.value}
                          htmlFor={`size-${size.value}`}
                          className={cn(
                            'flex cursor-pointer items-start gap-2 px-4 py-3 font-normal hover:bg-surface-hover',
                            i > 0 && 'border-l border-rule',
                          )}
                        >
                          <span className="flex h-5 items-center">
                            <RadioGroupItem id={`size-${size.value}`} value={size.value} />
                          </span>
                          <span className="flex flex-col gap-1">
                            <span className="font-medium">{size.label}</span>
                            <span className="text-12 text-ink-2 tabular-nums">
                              {size.cpu}，内存 {size.memory}
                            </span>
                          </span>
                        </Label>
                      ))}
                    </RadioGroup>
                  )}
                />
              </FormField>
            )}
          </EditSection>

          {values.tier === 'sandbox' && (
            <EditSection id="env-egress" title="出网">
              <FormField
                id="env-domain"
                label="允许访问的域名"
                hint="沙箱只能访问白名单里的域名，其余一律拒绝；*.example.com 匹配任意层级的子域名，不含 example.com 本身；不接受 IP、端口与路径"
                error={listError}
              >
                <Controller
                  control={form.control}
                  name="egress_allowlist"
                  render={({ field }) => (
                    <AllowlistEditor
                      id="env-domain"
                      value={field.value}
                      onChange={field.onChange}
                      itemErrors={itemErrors}
                      readOnly={readOnly}
                    />
                  )}
                />
              </FormField>
            </EditSection>
          )}

          {environment && (
            <EditSection id="env-revisions" title="修订历史">
              <DataRegion
                errorTitle="无法加载修订历史"
                errorSize="inline"
                skeleton={
                  <div aria-hidden className="flex flex-col gap-3">
                    {[0, 1, 2].map((i) => (
                      <Skeleton key={i} className="h-5 w-full" />
                    ))}
                  </div>
                }
              >
                <RevisionHistory environment={environment} me={me} />
              </DataRegion>
            </EditSection>
          )}
        </div>
      </EditPage>
      {environment && (
        <DangerConfirmDialog
          open={deleting}
          onOpenChange={setDeleting}
          title={`删除环境 ${environment.name}`}
          confirmLabel="删除环境"
          confirmName={environment.name}
          consequences={
            <>
              <p>删除后不能再用它创建会话，修订历史随之删除，不能恢复。</p>
              <p>正在使用它的会话继续使用创建时的修订，直到归档或删除。</p>
            </>
          }
          onConfirm={async () => {
            await remove.mutateAsync(environment.id);
            void navigate({ to: '/projects/$projectId/environments', params: { projectId } });
          }}
        />
      )}
    </>
  );
}

function Loaded({ environmentId }: { environmentId: string }) {
  const { tenantId, projectId } = useProject();
  const environment = useSuspenseQuery(environmentQuery({ tenantId, projectId }, environmentId)).data;
  return <Editor key={environment.id} environment={environment} />;
}

const SKELETON_SAVE_BAR = {
  dirty: false,
  saveLabel: '保存',
  onSave: () => undefined,
  onDiscard: () => undefined,
};
const SKELETON_GROUPS = [
  { id: 'env-basic', label: '基本信息' },
  { id: 'env-tier', label: '执行档位' },
  { id: 'env-egress', label: '出网' },
  { id: 'env-revisions', label: '修订历史' },
];

/**
 * 环境的编辑与新建（编辑页模板）：执行档位、沙箱的资源规格、出网白名单与修订历史。档位、规格或白名单变化时保存为新修订
 * （保存按钮写明修订号），新会话立即使用新修订、进行中的会话不变；只改名字不产生修订。
 */
export function EnvironmentEditPage({ environmentId }: EnvironmentEditPageProps) {
  const read = usePermission('agent_read', '查看环境');
  if (!read.allowed)
    return (
      <DetailPage title="环境">
        <PermissionGate mode="explain" title="没有权限查看环境" allowed={false} reason={read.reason} />
      </DetailPage>
    );
  if (!environmentId) return <Editor environment={null} />;
  return (
    <DataRegion
      errorTitle="无法打开这个环境"
      skeleton={
        <EditPage title="编辑环境" loading groups={SKELETON_GROUPS} saveBar={SKELETON_SAVE_BAR}>
          {null}
        </EditPage>
      }
    >
      <Loaded environmentId={environmentId} />
    </DataRegion>
  );
}
