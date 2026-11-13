import { lazy, Suspense, type ReactNode } from 'react';
import { Controller, useFieldArray, useFormContext, useWatch } from 'react-hook-form';

import { FormField, fieldAria } from '@/components/domain/form-field';
import { StatusBadge } from '@/components/domain/status-badge';
import { EditSection } from '@/components/templates';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { InfoIcon, PlusIcon, TrashIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { RadioGroup, RadioGroupItem } from '@/components/ui/radio-group';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { formatInteger, formatTokens } from '@/lib/format';

import { fieldId } from '../../model/fields';
import {
  defaultSubagent,
  durationSeconds,
  MAX_SUBAGENT_TIMEOUT_SECONDS,
  secondsDuration,
  subagentToolChoices,
} from '../../model/spec';
import { PERMISSION_MODE_LABEL } from '../../model/labels';
import { isPlatformTool, mcpToolName, PLATFORM_TOOLS, TOOL_INFO } from '../../model/tools';

import { NumberInput } from './number-input';
import { PermissionRules } from './permission-rules';
import { ResourceAdd, ResourceSelect } from '@/components/domain/resource-picker';
import type { AgentForm, FieldErrors } from './use-agent-editor';
import type { AgentResources } from './use-resources';
import { mcpStatus } from './use-resources';

const PromptEditor = lazy(() => import('./prompt-editor'));

export interface SectionProps {
  errors: FieldErrors;
  /** 没有编辑权限：输入框只读，选择类控件不可用，不显示增删按钮。 */
  readOnly: boolean;
  resources: AgentResources;
}

/** 权限模式的选项（顺序同 schema 的枚举）。 */
const PERMISSION_MODES = (['default', 'accept_edits', 'explore', 'bypass', 'dont_ask'] as const).map(
  (value) => ({
    value,
    ...PERMISSION_MODE_LABEL[value]!,
  }),
);

/** 带 info 提示的小图标按钮（复选框旁的补充说明）。 */
function Hint({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <button
          type="button"
          aria-label={`${label}的说明`}
          className="inline-flex rounded-control text-ink-2 hover:text-ink"
        >
          <Icon glyph={InfoIcon} />
        </button>
      </TooltipTrigger>
      <TooltipContent className="max-w-80">{children}</TooltipContent>
    </Tooltip>
  );
}

/** 字段的 id 与校验错误。 */
function useField(errors: FieldErrors) {
  return (anchor: string) => {
    const id = fieldId(anchor);
    const error = errors[anchor];
    return { id, error, aria: fieldAria(id, error) };
  };
}

export function BasicSection({ errors, readOnly }: SectionProps) {
  const { register } = useFormContext<AgentForm>();
  const field = useField(errors);
  const name = field('name');
  const description = field('description');
  return (
    <EditSection id="basic" title="基本信息">
      <FormField
        id={name.id}
        label="名称"
        hint="项目内唯一；发布时写进规格，也是日志里的智能体名"
        error={name.error}
        className="max-w-100"
      >
        <Input id={name.id} readOnly={readOnly} className="font-mono" {...register('name')} {...name.aria} />
      </FormField>
      <FormField
        id={description.id}
        label="说明"
        hint="智能体列表与选择智能体时显示"
        error={description.error}
      >
        <Textarea
          id={description.id}
          rows={2}
          readOnly={readOnly}
          {...register('description')}
          {...description.aria}
        />
      </FormField>
    </EditSection>
  );
}

export function ModelSection({ errors, readOnly, resources }: SectionProps) {
  const { control } = useFormContext<AgentForm>();
  const field = useField(errors);
  const main = field('spec.model.model_id');
  const fallback = field('spec.model.fallback_model_id');
  const maxOutput = field('spec.model.max_output_tokens');
  const temperature = field('spec.model.temperature');
  const modelId = useWatch({ control, name: 'spec.model.model_id' });
  const model = resources.data.models.find((m) => m.id === modelId);
  return (
    <EditSection id="model" title="模型">
      <div className="grid grid-cols-2 gap-6">
        <FormField id={main.id} label="主模型" error={main.error}>
          <Controller
            control={control}
            name="spec.model.model_id"
            render={({ field: f }) => (
              <ResourceSelect
                id={main.id}
                value={f.value || null}
                onChange={(v) => f.onChange(v ?? '')}
                source={resources.models}
                searchLabel="搜索模型"
                emptyLabel="本租户的模型目录里还没有对话模型"
                disabled={readOnly}
                invalid={Boolean(main.error)}
                describedBy={main.error ? `${main.id}-error` : undefined}
              />
            )}
          />
        </FormField>
        <FormField
          id={fallback.id}
          label="备用模型"
          hint="主模型不可用时改用它；跟随模型目录时使用目录给主模型配置的备用模型"
          error={fallback.error}
        >
          <Controller
            control={control}
            name="spec.model.fallback_model_id"
            render={({ field: f }) => (
              <ResourceSelect
                id={fallback.id}
                value={f.value}
                onChange={f.onChange}
                source={resources.models}
                nullOption="跟随模型目录"
                searchLabel="搜索模型"
                emptyLabel="本租户的模型目录里还没有对话模型"
                disabled={readOnly}
                invalid={Boolean(fallback.error)}
                describedBy={fallback.error ? `${fallback.id}-error` : undefined}
              />
            )}
          />
        </FormField>
        <FormField id={maxOutput.id} label="单次最大输出 token" error={maxOutput.error}>
          <Controller
            control={control}
            name="spec.model.max_output_tokens"
            render={({ field: f }) => (
              <NumberInput
                id={maxOutput.id}
                value={f.value}
                onChange={f.onChange}
                onBlur={f.onBlur}
                readOnly={readOnly}
                placeholder={model ? `模型默认 ${formatInteger(model.max_output_tokens)}` : '模型默认'}
                {...maxOutput.aria}
              />
            )}
          />
        </FormField>
        <FormField id={temperature.id} label="温度" hint="0 到 2；分析类任务宜低" error={temperature.error}>
          <Controller
            control={control}
            name="spec.model.temperature"
            render={({ field: f }) => (
              <NumberInput
                id={temperature.id}
                decimal
                value={f.value}
                onChange={f.onChange}
                onBlur={f.onBlur}
                readOnly={readOnly}
                placeholder="模型默认"
                {...temperature.aria}
              />
            )}
          />
        </FormField>
      </div>
    </EditSection>
  );
}

const PROMPT_HEIGHT = 360;

export function PromptSection({ errors, readOnly }: SectionProps) {
  const { control } = useFormContext<AgentForm>();
  const prompt = useField(errors)('spec.system_prompt');
  const length = useWatch({ control, name: 'spec.system_prompt' }).length;
  return (
    <EditSection id="prompt" title="提示词">
      <FormField
        id={prompt.id}
        label="系统提示词"
        group
        hint="Markdown；平台基础提示词与租户提示词由平台拼接在它之前"
        error={prompt.error}
        aside={
          <span className="text-12 text-ink-2 tabular-nums" aria-label={`已写 ${length} 个字符，上限 32,000`}>
            {formatInteger(length)} / 32,000
          </span>
        }
      >
        <Controller
          control={control}
          name="spec.system_prompt"
          render={({ field: f }) => (
            <Suspense fallback={<Skeleton className="w-full" style={{ height: PROMPT_HEIGHT }} />}>
              <PromptEditor
                id={prompt.id}
                label="系统提示词"
                value={f.value}
                onChange={f.onChange}
                onBlur={f.onBlur}
                readOnly={readOnly}
                invalid={Boolean(prompt.error)}
                describedBy={prompt.error ? `${prompt.id}-error` : undefined}
                height={PROMPT_HEIGHT}
                placeholder="智能体的角色、口径与边界"
              />
            </Suspense>
          )}
        />
      </FormField>
    </EditSection>
  );
}

export function ToolsSection({ errors, readOnly, resources }: SectionProps) {
  const { control, setValue } = useFormContext<AgentForm>();
  const field = useField(errors);
  const tools = field('spec.tools');
  const tool = useWatch({ control, name: 'spec.tools' });
  const mounted = useWatch({ control, name: 'spec.mcp_servers' });
  const dirty = { shouldDirty: true } as const;
  const servers = resources.data.mcpServers;
  return (
    <EditSection id="tools" title="工具">
      <FormField id={tools.id} label="平台工具" group error={tools.error}>
        <div
          id={tools.id}
          role="group"
          aria-labelledby={`${tools.id}-label`}
          aria-describedby={tools.error ? `${tools.id}-error` : undefined}
          className="grid grid-cols-2 gap-x-6 gap-y-2"
        >
          {PLATFORM_TOOLS.map((name) => (
            <div key={name} className="flex items-center gap-2">
              <Checkbox
                id={`tool-${name}`}
                checked={tool.includes(name)}
                disabled={readOnly}
                onCheckedChange={(on) =>
                  setValue(
                    'spec.tools',
                    on === true
                      ? PLATFORM_TOOLS.filter((t) => t === name || tool.includes(t))
                      : tool.filter((t) => t !== name),
                    dirty,
                  )
                }
              />
              <Label htmlFor={`tool-${name}`} className="gap-2 font-normal">
                <span className="font-mono text-12">{name}</span>
                <span className="text-ink-2">{TOOL_INFO[name].label}</span>
              </Label>
              {TOOL_INFO[name].hint && <Hint label={name}>{TOOL_INFO[name].hint}</Hint>}
            </div>
          ))}
        </div>
      </FormField>

      <FormField id={fieldId('spec.mcp_servers')} label="MCP 服务" group>
        <ul
          aria-labelledby={`${fieldId('spec.mcp_servers')}-label`}
          className="flex flex-col border-t border-rule"
        >
          {mounted.length === 0 && (
            <li className="flex h-10 items-center text-13 text-ink-2">没有挂载 MCP 服务</li>
          )}
          {mounted.map((ref, index) => {
            const server = servers.find((s) => s.id === ref.mcp_server_id);
            const anchor = field(`spec.mcp_servers.${index}`);
            const status = server ? mcpStatus(server) : { kind: 'failure' as const, label: '已删除' };
            const all = ref.enabled_tools.length === 0;
            const update = (enabled: string[]) =>
              setValue(
                'spec.mcp_servers',
                mounted.map((m, i) => (i === index ? { ...m, enabled_tools: enabled } : m)),
                dirty,
              );
            return (
              <li key={ref.mcp_server_id} className="flex flex-col gap-3 border-b border-rule py-3">
                <div className="flex items-center gap-3">
                  <span className="min-w-0 truncate font-mono text-13 font-medium">
                    {server?.name ?? ref.mcp_server_id}
                  </span>
                  {status && <StatusBadge of="custom" kind={status.kind} label={status.label} />}
                  {server?.last_check?.message && (
                    <Hint label={server.name}>{server.last_check.message}</Hint>
                  )}
                  <span className="flex-1" />
                  {server && (
                    <RadioGroup
                      id={anchor.id}
                      value={all ? 'all' : 'some'}
                      disabled={readOnly}
                      aria-label={`${server.name} 启用的工具`}
                      className="flex gap-4"
                      onValueChange={(v) => update(v === 'all' ? [] : server.tools.map((t) => t.name))}
                    >
                      {(
                        [
                          ['all', '全部工具'],
                          ['some', '指定工具'],
                        ] as const
                      ).map(([value, text]) => (
                        <div key={value} className="flex items-center gap-2">
                          <RadioGroupItem id={`${anchor.id}-${value}`} value={value} />
                          <Label htmlFor={`${anchor.id}-${value}`} className="font-normal">
                            {text}
                          </Label>
                        </div>
                      ))}
                    </RadioGroup>
                  )}
                  {!readOnly && (
                    <Button
                      size="icon-sm"
                      variant="ghost"
                      aria-label={`移除 MCP 服务 ${server?.name ?? ref.mcp_server_id}`}
                      onClick={() =>
                        setValue(
                          'spec.mcp_servers',
                          mounted.filter((_, i) => i !== index),
                          dirty,
                        )
                      }
                    >
                      <Icon glyph={TrashIcon} />
                    </Button>
                  )}
                </div>
                {server && !all && (
                  <div
                    role="group"
                    aria-label={`${server.name} 的工具`}
                    className="flex flex-wrap gap-x-6 gap-y-2"
                  >
                    {server.tools.map((t) => (
                      <div key={t.name} className="flex items-center gap-2">
                        <Checkbox
                          id={`mcp-${server.id}-${t.name}`}
                          checked={ref.enabled_tools.includes(t.name)}
                          disabled={readOnly}
                          onCheckedChange={(on) =>
                            update(
                              on === true
                                ? [...ref.enabled_tools, t.name]
                                : ref.enabled_tools.filter((n) => n !== t.name),
                            )
                          }
                        />
                        <Label
                          htmlFor={`mcp-${server.id}-${t.name}`}
                          className="font-mono text-12 font-normal"
                        >
                          {t.name}
                        </Label>
                      </div>
                    ))}
                  </div>
                )}
                {anchor.error && (
                  <p id={`${anchor.id}-error`} className="text-12 text-danger">
                    {anchor.error}
                  </p>
                )}
              </li>
            );
          })}
        </ul>
        {!readOnly && (
          <ResourceAdd
            label="挂载 MCP 服务"
            searchLabel="搜索 MCP 服务"
            emptyLabel="本项目还没有登记 MCP 服务（在能力页登记）"
            source={{
              ...resources.mcpServers,
              options: resources.mcpServers.options.map((o) => ({
                ...o,
                disabled: mounted.some((m) => m.mcp_server_id === o.id),
              })),
            }}
            onAdd={(id) =>
              setValue('spec.mcp_servers', [...mounted, { mcp_server_id: id, enabled_tools: [] }], dirty)
            }
          />
        )}
      </FormField>

      <FormField id="custom-tools" label="自定义工具" group>
        <p className="flex items-center gap-1 text-13 text-ink-2">
          由业务系统在创建会话时声明，在业务侧执行
          <Hint label="自定义工具">
            创建会话时在 custom_tools 中声明工具的名字、说明与入参；名字匹配 ^[a-z][a-z0-9_]{'{0,63}'}
            $，不能与平台工具、 plan_enter 等保留名重名，也不能以 mcp__
            开头。会话中的调用作为待处理动作交给业务系统回传结果。
          </Hint>
        </p>
      </FormField>
    </EditSection>
  );
}

/** 规则可选的工具：平台工具、挂载的 MCP 工具。 */
function useToolGroups(resources: AgentResources) {
  const { control } = useFormContext<AgentForm>();
  const mounted = useWatch({ control, name: 'spec.mcp_servers' });
  const mcp = mounted.flatMap((ref) => {
    const server = resources.data.mcpServers.find((s) => s.id === ref.mcp_server_id);
    if (!server) return [];
    const names = ref.enabled_tools.length === 0 ? server.tools.map((t) => t.name) : ref.enabled_tools;
    return names.map((t) => mcpToolName(server.name, t));
  });
  return [
    { label: '平台工具', tools: PLATFORM_TOOLS },
    { label: 'MCP 工具', tools: mcp },
  ];
}

export function PermissionsSection({ errors, readOnly, resources }: SectionProps) {
  const { control } = useFormContext<AgentForm>();
  const field = useField(errors);
  const mode = field('spec.permissions.mode');
  const rules = field('spec.permissions.rules');
  const modeValue = useWatch({ control, name: 'spec.permissions.mode' });
  const kbIds = useWatch({ control, name: 'spec.knowledge.knowledge_base_ids' });
  const subagents = useWatch({ control, name: 'spec.subagents' }).map((s) => s.name);
  const toolGroups = useToolGroups(resources);
  const kbs = kbIds.map((id) => ({ id, name: resources.names.get(id) ?? id }));
  return (
    <EditSection id="permissions" title="权限">
      <FormField
        id={mode.id}
        label="权限模式"
        hint={PERMISSION_MODES.find((m) => m.value === modeValue)?.hint}
        error={mode.error}
        className="max-w-100"
      >
        <Controller
          control={control}
          name="spec.permissions.mode"
          render={({ field: f }) => (
            <Select value={f.value} disabled={readOnly} onValueChange={f.onChange}>
              <SelectTrigger id={mode.id} {...mode.aria}>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {PERMISSION_MODES.map((m) => (
                  <SelectItem key={m.value} value={m.value}>
                    {m.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}
        />
      </FormField>
      <FormField
        id={rules.id}
        label="规则"
        group
        hint="智能体层的规则，与平台、租户、项目、会话的规则合成后判定：拒绝优先于询问，询问优先于放行；默认模式下没有命中放行的调用都要确认"
      >
        <Controller
          control={control}
          name="spec.permissions.rules"
          render={({ field: f }) => (
            <PermissionRules
              id={rules.id}
              label="规则"
              rules={f.value}
              onChange={f.onChange}
              toolGroups={toolGroups}
              knowledgeBases={kbs}
              subagents={subagents}
              readOnly={readOnly}
              error={rules.error}
            />
          )}
        />
      </FormField>
    </EditSection>
  );
}

export function KnowledgeSection({ errors, readOnly, resources }: SectionProps) {
  const { control, setValue } = useFormContext<AgentForm>();
  const field = useField(errors);
  const bases = field('spec.knowledge.knowledge_base_ids');
  const topK = field('spec.knowledge.prefetch_top_k');
  const rewrite = field('spec.knowledge.query_rewrite_model');
  const ids = useWatch({ control, name: 'spec.knowledge.knowledge_base_ids' });
  const prefetch = useWatch({ control, name: 'spec.knowledge.prefetch' });
  const dirty = { shouldDirty: true } as const;
  return (
    <EditSection id="knowledge" title="知识库">
      <FormField id={bases.id} label="可检索的知识库" group error={bases.error}>
        <ul
          id={bases.id}
          aria-labelledby={`${bases.id}-label`}
          aria-describedby={bases.error ? `${bases.id}-error` : undefined}
          className="flex flex-col border-t border-rule"
        >
          {ids.length === 0 && <li className="flex h-10 items-center text-13 text-ink-2">没有绑定知识库</li>}
          {ids.map((id) => {
            const kb = resources.data.knowledgeBases.find((k) => k.id === id);
            return (
              <li key={id} className="flex h-10 items-center gap-3 border-b border-rule">
                <span className="min-w-0 truncate">
                  {kb?.name ?? <span className="font-mono text-12">{id}</span>}
                </span>
                {!kb && !resources.knowledgeBases.loading && (
                  <StatusBadge of="custom" kind="failure" label="已删除或不可用" />
                )}
                <span className="flex-1" />
                {kb && (
                  <span className="text-12 text-ink-2 tabular-nums">
                    {formatInteger(kb.document_count)} 份文档
                  </span>
                )}
                {!readOnly && (
                  <Button
                    size="icon-sm"
                    variant="ghost"
                    aria-label={`解除绑定 ${kb?.name ?? id}`}
                    onClick={() =>
                      setValue(
                        'spec.knowledge.knowledge_base_ids',
                        ids.filter((k) => k !== id),
                        dirty,
                      )
                    }
                  >
                    <Icon glyph={TrashIcon} />
                  </Button>
                )}
              </li>
            );
          })}
        </ul>
        {!readOnly && (
          <ResourceAdd
            label="绑定知识库"
            searchLabel="搜索知识库"
            emptyLabel="本项目还没有知识库"
            source={{
              ...resources.knowledgeBases,
              options: resources.knowledgeBases.options.map((o) => ({ ...o, disabled: ids.includes(o.id) })),
            }}
            onAdd={(id) => setValue('spec.knowledge.knowledge_base_ids', [...ids, id], dirty)}
          />
        )}
      </FormField>
      <div className="flex items-center gap-3">
        <Controller
          control={control}
          name="spec.knowledge.prefetch"
          render={({ field: f }) => (
            <Switch
              id={fieldId('spec.knowledge.prefetch')}
              checked={f.value}
              disabled={readOnly}
              onCheckedChange={f.onChange}
            />
          )}
        />
        <Label htmlFor={fieldId('spec.knowledge.prefetch')} className="font-normal">
          每轮开始前用最后一条用户消息检索，并把结果放进上下文
        </Label>
      </div>
      <div className="grid grid-cols-2 gap-6">
        <FormField id={topK.id} label="注入的切片数" error={topK.error}>
          <Controller
            control={control}
            name="spec.knowledge.prefetch_top_k"
            render={({ field: f }) => (
              <NumberInput
                id={topK.id}
                value={f.value}
                onChange={(v) => f.onChange(v ?? Number.NaN)}
                onBlur={f.onBlur}
                readOnly={readOnly}
                disabled={!prefetch}
                {...topK.aria}
              />
            )}
          />
        </FormField>
        <FormField
          id={rewrite.id}
          label="查询改写模型"
          hint="检索前用小模型改写查询，检索知识库与前置检索都生效；不改写时检索原句"
          error={rewrite.error}
        >
          <Controller
            control={control}
            name="spec.knowledge.query_rewrite_model"
            render={({ field: f }) => (
              <ResourceSelect
                id={rewrite.id}
                value={f.value}
                onChange={f.onChange}
                source={resources.models}
                nullOption="不改写"
                searchLabel="搜索模型"
                emptyLabel="本租户的模型目录里还没有对话模型"
                disabled={readOnly}
                invalid={Boolean(rewrite.error)}
                describedBy={rewrite.error ? `${rewrite.id}-error` : undefined}
              />
            )}
          />
        </FormField>
      </div>
    </EditSection>
  );
}

/** 技能引用的列表（主智能体与子智能体共用）。 */
function SkillList({
  id,
  label,
  value,
  onChange,
  readOnly,
  resources,
  error,
}: {
  id: string;
  label: string;
  value: readonly { skill_id: string; version: number }[];
  onChange: (next: { skill_id: string; version: number }[]) => void;
  readOnly: boolean;
  resources: AgentResources;
  error?: string | undefined;
}) {
  return (
    <>
      <ul
        id={id}
        aria-label={label}
        aria-describedby={error ? `${id}-error` : undefined}
        className="flex flex-col border-t border-rule"
      >
        {value.length === 0 && <li className="flex h-10 items-center text-13 text-ink-2">没有技能</li>}
        {value.map((ref) => {
          const skill = resources.data.skills.find((s) => s.id === ref.skill_id);
          const versions = Array.from(
            { length: Math.max(skill?.latest_version ?? 0, ref.version) },
            (_, i) => i + 1,
          );
          return (
            <li key={ref.skill_id} className="flex h-12 items-center gap-4 border-b border-rule">
              <span className="min-w-0 flex-1 truncate font-mono text-12">{skill?.name ?? ref.skill_id}</span>
              {!skill && !resources.skills.loading && (
                <StatusBadge of="custom" kind="failure" label="已删除" />
              )}
              <Select
                value={String(ref.version)}
                disabled={readOnly}
                onValueChange={(v) =>
                  onChange(value.map((s) => (s.skill_id === ref.skill_id ? { ...s, version: Number(v) } : s)))
                }
              >
                <SelectTrigger
                  size="sm"
                  className="w-28"
                  aria-label={`${skill?.name ?? ref.skill_id} 的版本`}
                >
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {versions.map((v) => (
                    <SelectItem key={v} value={String(v)}>
                      v{v}
                      {v === skill?.latest_version ? '（最新）' : ''}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {!readOnly && (
                <Button
                  size="icon-sm"
                  variant="ghost"
                  aria-label={`移除技能 ${skill?.name ?? ref.skill_id}`}
                  onClick={() => onChange(value.filter((s) => s.skill_id !== ref.skill_id))}
                >
                  <Icon glyph={TrashIcon} />
                </Button>
              )}
            </li>
          );
        })}
      </ul>
      {!readOnly && (
        <ResourceAdd
          label="添加技能"
          searchLabel="搜索技能"
          emptyLabel="本项目还没有技能（在能力页上传）"
          source={{
            ...resources.skills,
            options: resources.skills.options.map((o) => ({
              ...o,
              disabled: value.some((s) => s.skill_id === o.id),
            })),
          }}
          onAdd={(skillId) => {
            const skill = resources.data.skills.find((s) => s.id === skillId);
            onChange([...value, { skill_id: skillId, version: skill?.latest_version ?? 1 }]);
          }}
        />
      )}
    </>
  );
}

export function SkillsSection({ errors, readOnly, resources }: SectionProps) {
  const { control } = useFormContext<AgentForm>();
  const skills = useField(errors)('spec.skills');
  return (
    <EditSection id="skills" title="技能">
      <FormField
        id={skills.id}
        label="可用技能"
        group
        error={skills.error}
        hint="引用到具体版本；技能上传新版本后，这里仍用选定的版本"
      >
        <Controller
          control={control}
          name="spec.skills"
          render={({ field: f }) => (
            <SkillList
              id={skills.id}
              label="可用技能"
              value={f.value}
              onChange={f.onChange}
              readOnly={readOnly}
              resources={resources}
              error={skills.error}
            />
          )}
        />
      </FormField>
    </EditSection>
  );
}

function SubagentFields({
  index,
  errors,
  readOnly,
  resources,
  onRemove,
}: SectionProps & { index: number; onRemove: () => void }) {
  const { control, register } = useFormContext<AgentForm>();
  const field = useField(errors);
  const base = `spec.subagents.${index}` as const;
  const name = field(`${base}.name`);
  const description = field(`${base}.description`);
  const model = field(`${base}.model.model_id`);
  const maxIters = field(`${base}.max_iters`);
  const timeout = field(`${base}.timeout`);
  const tools = field(`${base}.tools`);
  const prompt = field(`${base}.system_prompt`);
  const skills = field(`${base}.skills`);
  const rules = field(`${base}.permissions`);
  const subName = useWatch({ control, name: `${base}.name` });
  const subTools = useWatch({ control, name: `${base}.tools` });
  const parentTools = useWatch({ control, name: 'spec.tools' });
  const mounted = useWatch({ control, name: 'spec.mcp_servers' });
  const kbIds = useWatch({ control, name: 'spec.knowledge.knowledge_base_ids' });
  const mcpTools = new Map(
    resources.data.mcpServers.map((s) => [s.id, { name: s.name, tools: s.tools.map((t) => t.name) }]),
  );
  const choices = subagentToolChoices({ tools: parentTools, mcp_servers: mounted }, mcpTools);
  const shown = [...new Set([...choices, ...subTools])];
  const display = subName || `子智能体 ${index + 1}`;
  return (
    <fieldset className="flex flex-col gap-4 border-t border-rule pt-4">
      <legend className="sr-only">子智能体 {display}</legend>
      <div className="grid grid-cols-[minmax(0,1fr)_auto] items-end gap-3">
        <FormField
          id={name.id}
          label="名称"
          error={name.error}
          hint="父智能体拉起它时用的名字（spawn_subagent 的 subagent 参数）"
          className="max-w-80"
        >
          <Input
            id={name.id}
            readOnly={readOnly}
            className="font-mono"
            {...register(`${base}.name`)}
            {...name.aria}
          />
        </FormField>
        {!readOnly && (
          <Button
            variant="ghost"
            icon={<Icon glyph={TrashIcon} />}
            onClick={onRemove}
            aria-label={`移除子智能体 ${display}`}
          >
            移除
          </Button>
        )}
      </div>
      <FormField
        id={description.id}
        label="用途"
        hint="给父智能体看：什么时候拉起它"
        error={description.error}
      >
        <Input
          id={description.id}
          readOnly={readOnly}
          {...register(`${base}.description`)}
          {...description.aria}
        />
      </FormField>
      <div className="grid grid-cols-3 gap-6">
        <FormField id={model.id} label="模型" error={model.error}>
          <Controller
            control={control}
            name={`${base}.model.model_id`}
            render={({ field: f }) => (
              <ResourceSelect
                id={model.id}
                value={f.value || null}
                onChange={(v) => f.onChange(v ?? '')}
                source={resources.models}
                searchLabel="搜索模型"
                emptyLabel="本租户的模型目录里还没有对话模型"
                disabled={readOnly}
                invalid={Boolean(model.error)}
                describedBy={model.error ? `${model.id}-error` : undefined}
              />
            )}
          />
        </FormField>
        <FormField id={maxIters.id} label="最大推理次数" error={maxIters.error}>
          <Controller
            control={control}
            name={`${base}.max_iters`}
            render={({ field: f }) => (
              <NumberInput
                id={maxIters.id}
                value={f.value}
                onChange={(v) => f.onChange(v ?? Number.NaN)}
                onBlur={f.onBlur}
                readOnly={readOnly}
                {...maxIters.aria}
              />
            )}
          />
        </FormField>
        <FormField
          id={timeout.id}
          label="超时（秒）"
          hint={`单次执行的上限，至多 ${MAX_SUBAGENT_TIMEOUT_SECONDS} 秒`}
          error={timeout.error}
        >
          <Controller
            control={control}
            name={`${base}.timeout`}
            render={({ field: f }) => {
              const seconds = durationSeconds(f.value);
              return (
                <NumberInput
                  id={timeout.id}
                  value={Number.isNaN(seconds) ? null : seconds}
                  onChange={(v) => f.onChange(v === null || Number.isNaN(v) ? '' : secondsDuration(v))}
                  onBlur={f.onBlur}
                  readOnly={readOnly}
                  {...timeout.aria}
                />
              );
            }}
          />
        </FormField>
      </div>
      <FormField
        id={tools.id}
        label="可用工具"
        group
        error={tools.error}
        hint="只能从本智能体启用的平台工具与挂载的 MCP 工具中选；子智能体不能再拉起子智能体"
      >
        <Controller
          control={control}
          name={`${base}.tools`}
          render={({ field: f }) => (
            <div
              id={tools.id}
              role="group"
              aria-labelledby={`${tools.id}-label`}
              aria-describedby={tools.error ? `${tools.id}-error` : undefined}
              className="flex flex-wrap gap-x-6 gap-y-2"
            >
              {shown.length === 0 && (
                <span className="text-13 text-ink-2">本智能体还没有可分给子智能体的工具</span>
              )}
              {shown.map((t) => {
                const allowed = choices.includes(t);
                return (
                  <div key={t} className="flex items-center gap-2">
                    <Checkbox
                      id={`${tools.id}-${t}`}
                      checked={f.value.includes(t)}
                      disabled={readOnly}
                      aria-invalid={!allowed || undefined}
                      onCheckedChange={(on) =>
                        f.onChange(on === true ? [...f.value, t] : f.value.filter((x) => x !== t))
                      }
                    />
                    <Label
                      htmlFor={`${tools.id}-${t}`}
                      className={
                        allowed
                          ? 'font-mono text-12 font-normal'
                          : 'font-mono text-12 font-normal text-danger'
                      }
                    >
                      {t}
                    </Label>
                  </div>
                );
              })}
            </div>
          )}
        />
      </FormField>
      <FormField id={prompt.id} label="系统提示词" error={prompt.error}>
        <Textarea
          id={prompt.id}
          rows={4}
          readOnly={readOnly}
          className="leading-body"
          {...register(`${base}.system_prompt`)}
          {...prompt.aria}
        />
      </FormField>
      <FormField id={skills.id} label="技能" group error={skills.error}>
        <Controller
          control={control}
          name={`${base}.skills`}
          render={({ field: f }) => (
            <SkillList
              id={skills.id}
              label={`${display} 的技能`}
              value={f.value}
              onChange={f.onChange}
              readOnly={readOnly}
              resources={resources}
              error={skills.error}
            />
          )}
        />
      </FormField>
      <FormField
        id={rules.id}
        label="放行规则"
        group
        hint="子智能体无人审批：没有命中放行规则的调用直接拒绝；这里的放行规则只有被本智能体的放行规则覆盖时才生效"
      >
        <Controller
          control={control}
          name={`${base}.permissions.rules`}
          render={({ field: f }) => (
            <PermissionRules
              id={rules.id}
              label={`${display} 的放行规则`}
              rules={f.value}
              onChange={f.onChange}
              toolGroups={[
                { label: '平台工具', tools: subTools.filter(isPlatformTool) },
                { label: 'MCP 工具', tools: subTools.filter((t) => !isPlatformTool(t)) },
              ]}
              knowledgeBases={kbIds.map((id) => ({ id, name: resources.names.get(id) ?? id }))}
              subagents={[]}
              behaviors={['allow']}
              readOnly={readOnly}
              error={rules.error}
            />
          )}
        />
      </FormField>
    </fieldset>
  );
}

export function SubagentsSection(props: SectionProps) {
  const { readOnly } = props;
  const { control, getValues } = useFormContext<AgentForm>();
  const list = useFieldArray({ control, name: 'spec.subagents' });
  const add = () => {
    const taken = new Set(getValues('spec.subagents').map((s) => s.name));
    let n = list.fields.length + 1;
    while (taken.has(`subagent-${n}`)) n += 1;
    list.append(defaultSubagent(`subagent-${n}`, getValues('spec.model.model_id')), {
      focusName: `spec.subagents.${list.fields.length}.name`,
    });
  };
  return (
    <EditSection id="subagents" title="子智能体">
      {list.fields.length === 0 && <p className="text-13 text-ink-2">没有子智能体</p>}
      {list.fields.map((item, index) => (
        <SubagentFields key={item.id} {...props} index={index} onRemove={() => list.remove(index)} />
      ))}
      {!readOnly && (
        <Button
          size="sm"
          variant="ghost"
          className="-ml-2 self-start"
          icon={<Icon glyph={PlusIcon} />}
          onClick={add}
        >
          添加子智能体
        </Button>
      )}
    </EditSection>
  );
}

export function RuntimeSection({ errors, readOnly, resources }: SectionProps) {
  const { control } = useFormContext<AgentForm>();
  const field = useField(errors);
  const maxIters = field('spec.max_iters');
  const reserved = field('spec.compaction.reserved_tokens');
  const modelId = useWatch({ control, name: 'spec.model.model_id' });
  const reservedValue = useWatch({ control, name: 'spec.compaction.reserved_tokens' });
  const model = resources.data.models.find((m) => m.id === modelId);
  const threshold = model && Number.isFinite(reservedValue) ? model.context_window - reservedValue : null;
  return (
    <EditSection id="runtime" title="上下文与计划">
      <div className="grid grid-cols-2 gap-6">
        <FormField id={maxIters.id} label="单轮最大推理次数" error={maxIters.error}>
          <Controller
            control={control}
            name="spec.max_iters"
            render={({ field: f }) => (
              <NumberInput
                id={maxIters.id}
                value={f.value}
                onChange={(v) => f.onChange(v ?? Number.NaN)}
                onBlur={f.onBlur}
                readOnly={readOnly}
                {...maxIters.aria}
              />
            )}
          />
        </FormField>
        <FormField
          id={reserved.id}
          label="压缩时预留的 token"
          hint="上下文超过主模型的上下文窗口减去这个数时，平台压缩较早的历史"
          error={reserved.error}
          aside={
            threshold !== null && (
              <span className="text-12 text-ink-2 tabular-nums">
                {threshold > 0 ? `超过 ${formatTokens(threshold)} 时压缩` : '预留超过了上下文窗口'}
              </span>
            )
          }
        >
          <Controller
            control={control}
            name="spec.compaction.reserved_tokens"
            render={({ field: f }) => (
              <NumberInput
                id={reserved.id}
                value={f.value}
                onChange={(v) => f.onChange(v ?? Number.NaN)}
                onBlur={f.onBlur}
                readOnly={readOnly}
                {...reserved.aria}
              />
            )}
          />
        </FormField>
      </div>
      {(
        [
          [
            'spec.plan_mode',
            '计划模式：先写计划，经人确认后再执行',
            '启用 plan_enter、plan_write、plan_exit；无人值守的会话不能进入计划模式',
          ],
          ['spec.expose_thinking', '在会话事件中带出思考内容', '模型输出的推理内容写进 agent.thinking 事件'],
        ] as const
      ).map(([name, label, hint]) => (
        <div key={name} className="flex items-center gap-3">
          <Controller
            control={control}
            name={name}
            render={({ field: f }) => (
              <Switch id={fieldId(name)} checked={f.value} disabled={readOnly} onCheckedChange={f.onChange} />
            )}
          />
          <Label htmlFor={fieldId(name)} className="font-normal">
            {label}
          </Label>
          <Hint label={label}>{hint}</Hint>
        </div>
      ))}
    </EditSection>
  );
}
