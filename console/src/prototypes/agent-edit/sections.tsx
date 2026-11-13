import { StatusBadge } from '@/components/domain/status-badge';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { PlusIcon, TrashIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Switch } from '@/components/ui/switch';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Textarea } from '@/components/ui/textarea';
import { formatInteger } from '@/lib/format';

import {
  KNOWLEDGE_BASES,
  MCP_SERVERS,
  MODELS,
  PERMISSION_MODES,
  PLATFORM_TOOLS,
  SKILLS,
  type AgentDefinitionForm,
  type PermissionMode,
  type PlatformTool,
  type RuleBehavior,
} from '../fixtures/agent-definition';
import { Field, fieldAria } from '../shared/field';
import { EditSection } from '@/components/templates';

import type { FormErrors } from './validate';

export interface SectionProps {
  form: AgentDefinitionForm;
  errors: FormErrors;
  /** 只读（没有编辑权限）：输入框只读，选择类控件不可用。 */
  readOnly: boolean;
  onChange: (patch: Partial<AgentDefinitionForm>) => void;
}

/** 选择器里"跟随默认"的取值（Radix Select 不接受空串）。 */
const DEFAULT = '__default__';

function numberOrNull(text: string): number | null {
  const t = text.trim();
  return t === '' ? null : Number(t);
}

function ModelSelect({
  id,
  value,
  onChange,
  disabled,
  defaultLabel,
}: {
  id: string;
  value: string | null;
  onChange: (value: string | null) => void;
  disabled: boolean;
  defaultLabel?: string;
}) {
  return (
    <Select
      value={value ?? DEFAULT}
      onValueChange={(v) => onChange(v === DEFAULT ? null : v)}
      disabled={disabled}
    >
      <SelectTrigger id={id}>
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {defaultLabel && <SelectItem value={DEFAULT}>{defaultLabel}</SelectItem>}
        {MODELS.map((m) => (
          <SelectItem key={m.id} value={m.id}>
            <span className="font-mono text-12">{m.name}</span>
            <span className="ml-2 text-ink-2">{m.provider}</span>
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

export function BasicSection({ form, errors, readOnly, onChange }: SectionProps) {
  return (
    <EditSection id="basic" title="基本信息">
      <Field id="name" label="名称" error={errors['name']} className="max-w-100">
        <Input
          id="name"
          value={form.name}
          readOnly={readOnly}
          onChange={(e) => onChange({ name: e.target.value })}
          {...fieldAria('name', errors['name'])}
        />
      </Field>
      <Field id="description" label="说明" hint="会话列表与选择智能体时显示">
        <Textarea
          id="description"
          rows={2}
          value={form.description}
          readOnly={readOnly}
          onChange={(e) => onChange({ description: e.target.value })}
        />
      </Field>
    </EditSection>
  );
}

export function ModelSection({ form, errors, readOnly, onChange }: SectionProps) {
  const model = MODELS.find((m) => m.id === form.modelId);
  return (
    <EditSection id="model" title="模型">
      <div className="grid grid-cols-2 gap-6">
        <Field id="model-main" label="主模型">
          <ModelSelect
            id="model-main"
            value={form.modelId}
            disabled={readOnly}
            onChange={(v) => v && onChange({ modelId: v })}
          />
        </Field>
        <Field
          id="model-fallback"
          label="备用模型"
          hint="主模型不可用时改用它；跟随模型目录时使用目录给主模型配置的备用模型"
        >
          <ModelSelect
            id="model-fallback"
            value={form.fallbackModelId}
            disabled={readOnly}
            defaultLabel="跟随模型目录"
            onChange={(v) => onChange({ fallbackModelId: v })}
          />
        </Field>
        <Field id="model-max-output" label="单次最大输出 token" error={errors['model-max-output']}>
          <Input
            id="model-max-output"
            inputMode="numeric"
            placeholder={model ? `模型默认 ${formatInteger(model.defaultMaxOutput)}` : undefined}
            value={form.maxOutputTokens ?? ''}
            readOnly={readOnly}
            onChange={(e) => onChange({ maxOutputTokens: numberOrNull(e.target.value) })}
            {...fieldAria('model-max-output', errors['model-max-output'])}
          />
        </Field>
        <Field
          id="model-temperature"
          label="温度"
          hint="0 到 2；分析类任务宜低"
          error={errors['model-temperature']}
        >
          <Input
            id="model-temperature"
            inputMode="decimal"
            placeholder="模型默认"
            value={form.temperature ?? ''}
            readOnly={readOnly}
            onChange={(e) => onChange({ temperature: numberOrNull(e.target.value) })}
            {...fieldAria('model-temperature', errors['model-temperature'])}
          />
        </Field>
      </div>
    </EditSection>
  );
}

export function PromptSection({ form, errors, readOnly, onChange }: SectionProps) {
  return (
    <EditSection id="prompt" title="提示词">
      <Field
        id="system-prompt"
        label="系统提示词"
        hint="平台基础提示词与租户提示词由平台拼接在它之前"
        error={errors['system-prompt']}
      >
        <Textarea
          id="system-prompt"
          rows={16}
          value={form.systemPrompt}
          readOnly={readOnly}
          onChange={(e) => onChange({ systemPrompt: e.target.value })}
          className="leading-body"
          {...fieldAria('system-prompt', errors['system-prompt'])}
        />
      </Field>
    </EditSection>
  );
}

const BEHAVIOR: Record<RuleBehavior, { kind: 'success' | 'requires_action' | 'failure'; label: string }> = {
  allow: { kind: 'success', label: '放行' },
  ask: { kind: 'requires_action', label: '询问' },
  deny: { kind: 'failure', label: '拒绝' },
};

export function ToolsSection({ form, errors, readOnly, onChange }: SectionProps) {
  const mode = PERMISSION_MODES.find((m) => m.value === form.permissionMode);
  const toggle = (tool: PlatformTool, on: boolean) =>
    onChange({ tools: on ? [...form.tools, tool] : form.tools.filter((t) => t !== tool) });
  return (
    <EditSection id="tools" title="工具与权限">
      <Field id="tools" label="平台工具" group error={errors['tools']}>
        <div
          role="group"
          aria-labelledby="tools-label"
          {...fieldAria('tools', errors['tools'])}
          className="grid grid-cols-2 gap-x-6 gap-y-2"
        >
          {PLATFORM_TOOLS.map((tool) => (
            <div key={tool.name} className="flex items-center gap-2">
              <Checkbox
                id={`tool-${tool.name}`}
                checked={form.tools.includes(tool.name)}
                disabled={readOnly}
                onCheckedChange={(v) => toggle(tool.name, v === true)}
              />
              <Label htmlFor={`tool-${tool.name}`} className="gap-2 font-normal">
                <span className="font-mono text-12">{tool.name}</span>
                <span className="text-ink-2">{tool.label}</span>
              </Label>
            </div>
          ))}
        </div>
      </Field>
      <Field id="tools-mode" label="权限模式" hint={mode?.hint} className="max-w-100">
        <Select
          value={form.permissionMode}
          disabled={readOnly}
          onValueChange={(v) => onChange({ permissionMode: v as PermissionMode })}
        >
          <SelectTrigger id="tools-mode">
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
      </Field>
      <Field id="rules" label="规则" group hint="按声明顺序判定；规则内容为空表示整个工具">
        <Table aria-labelledby="rules-label" className="table-fixed">
          <colgroup>
            <col />
            <col />
            <col style={{ width: 104 }} />
            <col style={{ width: 48 }} />
          </colgroup>
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead>工具</TableHead>
              <TableHead>规则内容</TableHead>
              <TableHead>结果</TableHead>
              <TableHead>
                <span className="sr-only">操作</span>
              </TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {form.rules.map((rule) => (
              <TableRow key={rule.key}>
                <TableCell className="truncate font-mono text-12">{rule.toolName}</TableCell>
                <TableCell className="truncate font-mono text-12">
                  {rule.ruleContent ?? <span className="font-sans text-13 text-ink-2">整个工具</span>}
                </TableCell>
                <TableCell>
                  <StatusBadge
                    of="custom"
                    kind={BEHAVIOR[rule.behavior].kind}
                    label={BEHAVIOR[rule.behavior].label}
                  />
                </TableCell>
                <TableCell className="text-right">
                  {!readOnly && (
                    <Button
                      size="icon-sm"
                      variant="ghost"
                      aria-label={`删除规则 ${rule.toolName}${rule.ruleContent ? `(${rule.ruleContent})` : ''}`}
                      onClick={() => onChange({ rules: form.rules.filter((r) => r.key !== rule.key) })}
                    >
                      <Icon glyph={TrashIcon} />
                    </Button>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {!readOnly && (
          <Button size="sm" variant="ghost" className="-ml-2 self-start" icon={<Icon glyph={PlusIcon} />}>
            添加规则
          </Button>
        )}
      </Field>
    </EditSection>
  );
}

export function KnowledgeSection({ form, errors, readOnly, onChange }: SectionProps) {
  return (
    <EditSection id="knowledge" title="知识库">
      <Field id="knowledge-bases" label="可检索的知识库" group error={errors['knowledge-bases']}>
        <div
          role="group"
          aria-labelledby="knowledge-bases-label"
          {...fieldAria('knowledge-bases', errors['knowledge-bases'])}
          className="flex flex-col gap-2"
        >
          {KNOWLEDGE_BASES.map((kb) => (
            <div key={kb.id} className="flex items-center gap-2">
              <Checkbox
                id={`kb-${kb.id}`}
                checked={form.knowledgeBaseIds.includes(kb.id)}
                disabled={readOnly}
                onCheckedChange={(v) =>
                  onChange({
                    knowledgeBaseIds:
                      v === true
                        ? [...form.knowledgeBaseIds, kb.id]
                        : form.knowledgeBaseIds.filter((id) => id !== kb.id),
                  })
                }
              />
              <Label htmlFor={`kb-${kb.id}`} className="gap-3 font-normal">
                {kb.name}
                <span className="text-12 text-ink-2 tabular-nums">{kb.documents} 份文档</span>
              </Label>
            </div>
          ))}
        </div>
      </Field>
      <div className="flex items-center gap-3">
        <Switch
          id="knowledge-prefetch"
          checked={form.prefetch}
          disabled={readOnly}
          onCheckedChange={(v) => onChange({ prefetch: v })}
        />
        <Label htmlFor="knowledge-prefetch" className="font-normal">
          每轮开始前用最后一条用户消息检索，并把结果放进上下文
        </Label>
      </div>
      <div className="grid grid-cols-2 gap-6">
        <Field id="knowledge-top-k" label="注入的切片数" error={errors['knowledge-top-k']}>
          <Input
            id="knowledge-top-k"
            inputMode="numeric"
            value={form.prefetchTopK}
            readOnly={readOnly}
            disabled={!form.prefetch}
            onChange={(e) => onChange({ prefetchTopK: Number(e.target.value) })}
            {...fieldAria('knowledge-top-k', errors['knowledge-top-k'])}
          />
        </Field>
        <Field id="knowledge-rewrite" label="查询改写模型" hint="检索前用小模型改写查询；不改写时检索原句">
          <ModelSelect
            id="knowledge-rewrite"
            value={form.queryRewriteModelId}
            disabled={readOnly}
            defaultLabel="不改写"
            onChange={(v) => onChange({ queryRewriteModelId: v })}
          />
        </Field>
      </div>
    </EditSection>
  );
}

export function ExtensionsSection({ form, readOnly, onChange }: SectionProps) {
  return (
    <EditSection id="extensions" title="技能与 MCP">
      <Field id="skills" label="技能" group>
        <ul aria-labelledby="skills-label" className="flex flex-col border-t border-rule">
          {form.skills.map((ref) => {
            const skill = SKILLS.find((s) => s.id === ref.skillId);
            return (
              <li key={ref.skillId} className="flex h-12 items-center gap-4 border-b border-rule">
                <span className="min-w-0 flex-1 truncate font-mono text-12">
                  {skill?.name ?? ref.skillId}
                </span>
                <Select
                  value={String(ref.version)}
                  disabled={readOnly}
                  onValueChange={(v) =>
                    onChange({
                      skills: form.skills.map((s) =>
                        s.skillId === ref.skillId ? { ...s, version: Number(v) } : s,
                      ),
                    })
                  }
                >
                  <SelectTrigger size="sm" className="w-24" aria-label={`${skill?.name ?? ''} 的版本`}>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {(skill?.versions ?? [ref.version]).map((v) => (
                      <SelectItem key={v} value={String(v)}>
                        v{v}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                {!readOnly && (
                  <Button
                    size="icon-sm"
                    variant="ghost"
                    aria-label={`移除技能 ${skill?.name ?? ''}`}
                    onClick={() => onChange({ skills: form.skills.filter((s) => s.skillId !== ref.skillId) })}
                  >
                    <Icon glyph={TrashIcon} />
                  </Button>
                )}
              </li>
            );
          })}
        </ul>
        {!readOnly && (
          <Button size="sm" variant="ghost" className="-ml-2 self-start" icon={<Icon glyph={PlusIcon} />}>
            添加技能
          </Button>
        )}
      </Field>
      {MCP_SERVERS.map((server) => {
        const mounted = form.mcpServers.find((m) => m.mcpServerId === server.id);
        const setTools = (tools: string[]) =>
          onChange({
            mcpServers:
              tools.length === 0
                ? form.mcpServers.filter((m) => m.mcpServerId !== server.id)
                : mounted
                  ? form.mcpServers.map((m) =>
                      m.mcpServerId === server.id ? { ...m, enabledTools: tools } : m,
                    )
                  : [...form.mcpServers, { mcpServerId: server.id, enabledTools: tools }],
          });
        return (
          <Field key={server.id} id={`mcp-${server.name}`} label={`MCP 服务 ${server.name}`} group>
            <div
              role="group"
              aria-labelledby={`mcp-${server.name}-label`}
              className="flex flex-wrap gap-x-6 gap-y-2"
            >
              {server.tools.map((tool) => (
                <div key={tool} className="flex items-center gap-2">
                  <Checkbox
                    id={`mcp-${server.name}-${tool}`}
                    checked={mounted?.enabledTools.includes(tool) ?? false}
                    disabled={readOnly}
                    onCheckedChange={(v) => {
                      const current = mounted?.enabledTools ?? [];
                      setTools(v === true ? [...current, tool] : current.filter((t) => t !== tool));
                    }}
                  />
                  <Label htmlFor={`mcp-${server.name}-${tool}`} className="font-mono text-12 font-normal">
                    {tool}
                  </Label>
                </div>
              ))}
            </div>
          </Field>
        );
      })}
    </EditSection>
  );
}

export function SubagentsSection({ form, errors, readOnly, onChange }: SectionProps) {
  const available = [
    ...form.tools.filter((t) => t !== 'spawn_subagent'),
    ...form.mcpServers.flatMap((s) => {
      const server = MCP_SERVERS.find((m) => m.id === s.mcpServerId);
      return server ? s.enabledTools.map((t) => `mcp__${server.name}__${t}`) : [];
    }),
  ];
  const update = (index: number, patch: Partial<AgentDefinitionForm['subagents'][number]>) =>
    onChange({ subagents: form.subagents.map((s, i) => (i === index ? { ...s, ...patch } : s)) });
  return (
    <EditSection id="subagents" title="子智能体">
      {form.subagents.map((sub, i) => (
        <fieldset key={sub.name} className="flex flex-col gap-4 border-t border-rule pt-4">
          <legend className="sr-only">子智能体 {sub.name}</legend>
          <div className="flex items-center gap-3">
            <span className="flex-1 font-mono text-13 font-medium">{sub.name}</span>
            {!readOnly && (
              <Button
                size="icon-sm"
                variant="ghost"
                aria-label={`移除子智能体 ${sub.name}`}
                onClick={() => onChange({ subagents: form.subagents.filter((_, j) => j !== i) })}
              >
                <Icon glyph={TrashIcon} />
              </Button>
            )}
          </div>
          <Field id={`subagent-${i}-description`} label="用途" hint="给父智能体看：什么时候拉起它">
            <Input
              id={`subagent-${i}-description`}
              value={sub.description}
              readOnly={readOnly}
              onChange={(e) => update(i, { description: e.target.value })}
            />
          </Field>
          <div className="grid grid-cols-3 gap-6">
            <Field id={`subagent-${i}-model`} label="模型">
              <ModelSelect
                id={`subagent-${i}-model`}
                value={sub.modelId}
                disabled={readOnly}
                onChange={(v) => v && update(i, { modelId: v })}
              />
            </Field>
            <Field id={`subagent-${i}-max-iters`} label="最大推理次数">
              <Input
                id={`subagent-${i}-max-iters`}
                inputMode="numeric"
                value={sub.maxIters}
                readOnly={readOnly}
                onChange={(e) => update(i, { maxIters: Number(e.target.value) })}
              />
            </Field>
            <Field id={`subagent-${i}-timeout`} label="超时（秒）" error={errors[`subagent-${i}-timeout`]}>
              <Input
                id={`subagent-${i}-timeout`}
                inputMode="numeric"
                value={sub.timeoutSeconds}
                readOnly={readOnly}
                onChange={(e) => update(i, { timeoutSeconds: Number(e.target.value) })}
                {...fieldAria(`subagent-${i}-timeout`, errors[`subagent-${i}-timeout`])}
              />
            </Field>
          </div>
          <Field id={`subagent-${i}-tools`} label="可用工具" group error={errors[`subagent-${i}-tools`]}>
            <div
              role="group"
              aria-labelledby={`subagent-${i}-tools-label`}
              {...fieldAria(`subagent-${i}-tools`, errors[`subagent-${i}-tools`])}
              className="flex flex-wrap gap-x-6 gap-y-2"
            >
              {[...new Set([...available, ...sub.tools])].map((tool) => (
                <div key={tool} className="flex items-center gap-2">
                  <Checkbox
                    id={`subagent-${i}-tool-${tool}`}
                    checked={sub.tools.includes(tool)}
                    disabled={readOnly}
                    aria-invalid={!available.includes(tool) || undefined}
                    onCheckedChange={(v) =>
                      update(i, {
                        tools: v === true ? [...sub.tools, tool] : sub.tools.filter((t) => t !== tool),
                      })
                    }
                  />
                  <Label htmlFor={`subagent-${i}-tool-${tool}`} className="font-mono text-12 font-normal">
                    {tool}
                  </Label>
                </div>
              ))}
            </div>
          </Field>
        </fieldset>
      ))}
      {!readOnly && (
        <Button size="sm" variant="ghost" className="-ml-2 self-start" icon={<Icon glyph={PlusIcon} />}>
          添加子智能体
        </Button>
      )}
    </EditSection>
  );
}

export function LimitsSection({ form, errors, readOnly, onChange }: SectionProps) {
  return (
    <EditSection id="limits" title="运行限制">
      <div className="grid grid-cols-2 gap-6">
        <Field id="limits-max-iters" label="单轮最大推理次数" error={errors['limits-max-iters']}>
          <Input
            id="limits-max-iters"
            inputMode="numeric"
            value={form.maxIters}
            readOnly={readOnly}
            onChange={(e) => onChange({ maxIters: Number(e.target.value) })}
            {...fieldAria('limits-max-iters', errors['limits-max-iters'])}
          />
        </Field>
        <Field id="limits-reserved" label="压缩时预留的 token" hint="上下文超过模型窗口减去这个数时压缩历史">
          <Input
            id="limits-reserved"
            inputMode="numeric"
            value={form.reservedTokens}
            readOnly={readOnly}
            onChange={(e) => onChange({ reservedTokens: Number(e.target.value) })}
          />
        </Field>
      </div>
      <div className="flex items-center gap-3">
        <Switch
          id="limits-plan"
          checked={form.planMode}
          disabled={readOnly}
          onCheckedChange={(v) => onChange({ planMode: v })}
        />
        <Label htmlFor="limits-plan" className="font-normal">
          计划模式：先写计划，经人确认后再执行
        </Label>
      </div>
      <div className="flex items-center gap-3">
        <Switch
          id="limits-thinking"
          checked={form.exposeThinking}
          disabled={readOnly}
          onCheckedChange={(v) => onChange({ exposeThinking: v })}
        />
        <Label htmlFor="limits-thinking" className="font-normal">
          在会话事件中带出思考内容
        </Label>
      </div>
    </EditSection>
  );
}
