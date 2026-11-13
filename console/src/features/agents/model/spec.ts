/**
 * 智能体规格（草稿）的 Zod schema：与 `meridian-api/src/main/resources/schema/agent-spec.schema.json` 一致（`spec.test.ts`
 * 逐字段比对类型、取值范围、格式与枚举），再加上 control-plane 发布时检查的跨字段规则（`AgentSpecRules`、`AgentPublication`）。
 *
 * 表单直接以规格的 snake_case 结构为值：服务端的字段错误 `spec.subagents.0.tools.1` 去掉前缀就是表单路径（见 `fields.ts`）。
 * 标为"发布时填写"的快照字段（智能体 ID、MCP 服务的端点与凭据、技能名等）不进表单：草稿可以省略，发布时由 control-plane 写入。
 */
import { z } from 'zod';

import { isPlatformTool, mcpToolName, parseMcpToolName, PLATFORM_TOOLS, checkRuleContent } from './tools';

/** 智能体与子智能体的名字（`agent_name`）。 */
export const AGENT_NAME = /^[a-z][a-z0-9_-]{0,63}$/;
const MODEL_ID = /^mdl_[A-Za-z0-9_]{1,64}$/;
const MCP_ID = /^mcp_[A-Za-z0-9_]{1,64}$/;
const SKILL_ID = /^skl_[A-Za-z0-9_]{1,64}$/;
const KB_ID = /^kb_[A-Za-z0-9_]{1,64}$/;
const MCP_TOOL = /^mcp__[a-z][a-z0-9_]{0,31}__[A-Za-z0-9_-]{1,64}$/;
/** ISO 8601 时长，与 schema 的 `timeout` 相同。 */
const DURATION = /^PT(?=\d)(\d+H)?(\d+M)?(\d+(\.\d+)?S)?$/;

/** 子智能体超时的上限（架构设计「子智能体」）。 */
export const MAX_SUBAGENT_TIMEOUT_SECONDS = 600;

const NAME_MESSAGE = '小写字母开头，只用小写字母、数字、下划线与短横线，至多 64 个字符。';

const unique = <T extends z.ZodType>(item: T, message: string) =>
  z.array(item).refine((values) => new Set(values).size === values.length, message);

const int = (label: string) => z.number({ error: `${label}要填一个整数。` }).int(`${label}要填一个整数。`);

const modelSchema = z.strictObject({
  model_id: z.string({ error: '选择一个对话模型。' }).regex(MODEL_ID, '选择一个对话模型。'),
  fallback_model_id: z.string().regex(MODEL_ID).nullable(),
  max_output_tokens: int('最大输出').min(1, '最大输出至少为 1，留空则用模型的默认值。').nullable(),
  temperature: z
    .number({ error: '温度要填一个数。' })
    .min(0, '温度在 0 到 2 之间。')
    .max(2, '温度在 0 到 2 之间。')
    .nullable(),
});

const permissionRuleSchema = z.strictObject({
  tool_name: z.string().min(1, '选择规则作用的工具。').max(200),
  rule_content: z.string().max(1000, '规则内容至多 1000 个字符。').nullable(),
  behavior: z.enum(['allow', 'deny', 'ask']),
  layer: z.literal('agent_version'),
});

const permissionPolicySchema = z.strictObject({
  mode: z.enum(['default', 'accept_edits', 'explore', 'bypass', 'dont_ask']),
  rules: z.array(permissionRuleSchema).max(200, '规则至多 200 条。'),
});

const skillRefSchema = z.strictObject({
  skill_id: z.string().regex(SKILL_ID),
  version: int('技能版本').min(1),
});

const mcpServerRefSchema = z.strictObject({
  mcp_server_id: z.string().regex(MCP_ID),
  enabled_tools: unique(z.string().min(1).max(64), '启用的工具不能重复。'),
});

const subagentToolSchema = z
  .string()
  .refine(
    (tool) => (isPlatformTool(tool) && tool !== 'spawn_subagent') || MCP_TOOL.test(tool),
    '子智能体不能再拉起子智能体，只能用平台工具与 MCP 工具。',
  );

const subagentSchema = z.strictObject({
  name: z.string().regex(AGENT_NAME, NAME_MESSAGE),
  description: z
    .string()
    .min(1, '写明用途：父智能体据此决定什么时候拉起它。')
    .max(2000, '用途至多 2000 个字符。'),
  model: modelSchema,
  system_prompt: z.string().max(32000, '系统提示词至多 32,000 个字符。'),
  tools: unique(subagentToolSchema, '工具不能重复。'),
  skills: z.array(skillRefSchema).max(32, '技能至多 32 个。'),
  permissions: permissionPolicySchema,
  max_iters: int('最大推理次数')
    .min(1, '最大推理次数在 1 到 200 之间。')
    .max(200, '最大推理次数在 1 到 200 之间。'),
  timeout: z.string().regex(DURATION, `超时在 1 到 ${MAX_SUBAGENT_TIMEOUT_SECONDS} 秒之间。`),
});

const knowledgeSchema = z.strictObject({
  knowledge_base_ids: unique(z.string().regex(KB_ID), '知识库不能重复。').max(32, '知识库至多 32 个。'),
  prefetch: z.boolean(),
  prefetch_top_k: int('注入的切片数')
    .min(1, '注入的切片数在 1 到 50 之间。')
    .max(50, '注入的切片数在 1 到 50 之间。'),
  query_rewrite_model: z.string().regex(MODEL_ID).nullable(),
});

/** 草稿规格的结构（JSON Schema 中除"发布时填写"之外的全部字段）。 */
export const draftSpecSchema = z.strictObject({
  model: modelSchema,
  system_prompt: z.string().max(32000, '系统提示词至多 32,000 个字符。'),
  tools: unique(z.enum(PLATFORM_TOOLS), '工具不能重复。'),
  mcp_servers: z.array(mcpServerRefSchema).max(16, 'MCP 服务至多 16 个。'),
  skills: z.array(skillRefSchema).max(32, '技能至多 32 个。'),
  subagents: z.array(subagentSchema).max(16, '子智能体至多 16 个。'),
  permissions: permissionPolicySchema,
  compaction: z.strictObject({ reserved_tokens: int('预留的 token').min(1, '预留的 token 至少为 1。') }),
  plan_mode: z.boolean(),
  knowledge: knowledgeSchema,
  max_iters: int('单轮最大推理次数')
    .min(1, '单轮最大推理次数在 1 到 200 之间。')
    .max(200, '单轮最大推理次数在 1 到 200 之间。'),
  expose_thinking: z.boolean(),
});

export type DraftSpec = z.infer<typeof draftSpecSchema>;
export type ModelRef = DraftSpec['model'];
export type PermissionPolicy = DraftSpec['permissions'];
export type PermissionRule = PermissionPolicy['rules'][number];
export type PermissionMode = PermissionPolicy['mode'];
export type RuleBehavior = PermissionRule['behavior'];
export type SubagentSpec = DraftSpec['subagents'][number];
export type SkillRef = DraftSpec['skills'][number];
export type McpServerRef = DraftSpec['mcp_servers'][number];

/** 规格引用的资源在本页的名字：MCP 服务 ID → 服务名（子智能体的 MCP 工具按服务名书写）。 */
export interface SpecContext {
  mcpServerNames: ReadonlyMap<string, string>;
}

/** ISO 8601 时长换成秒；不合法时为 NaN。 */
export function durationSeconds(iso: string): number {
  const match = DURATION.exec(iso);
  if (!match) return Number.NaN;
  const [, h, m, s] = match;
  return (h ? parseInt(h, 10) * 3600 : 0) + (m ? parseInt(m, 10) * 60 : 0) + (s ? parseFloat(s) : 0);
}

/** 秒换成 ISO 8601 时长（`PT300S`）。 */
export function secondsDuration(seconds: number): string {
  return `PT${seconds}S`;
}

/** 父智能体的工具集：启用的平台工具加挂载的 MCP 工具全名（启用清单为空表示该服务的全部工具，用 `mcp__name__*` 表示）。 */
export function parentToolSet(spec: Pick<DraftSpec, 'tools' | 'mcp_servers'>, context: SpecContext) {
  const platform = new Set<string>(spec.tools.filter((t) => t !== 'spawn_subagent'));
  const mounted = new Map<string, ReadonlySet<string> | 'all'>();
  for (const ref of spec.mcp_servers) {
    const name = context.mcpServerNames.get(ref.mcp_server_id);
    if (name) mounted.set(name, ref.enabled_tools.length === 0 ? 'all' : new Set(ref.enabled_tools));
  }
  return {
    has(tool: string): boolean {
      const mcp = parseMcpToolName(tool);
      if (!mcp) return platform.has(tool);
      const enabled = mounted.get(mcp.server);
      return enabled === 'all' || (enabled !== undefined && enabled.has(mcp.tool));
    },
  };
}

/**
 * 发布时检查的跨字段规则（与 `AgentSpecRules`、`AgentPublication` 一致）：MCP 服务不重复挂载、子智能体名不重复、声明了子智能体
 * 时启用 `spawn_subagent`、子智能体的工具是父智能体工具集的子集、子智能体超时在 (0, 600 秒]、开启前置检索时至少绑定一个知识库；
 * 另加权限规则内容的本地语法检查（`tools.ts`）。路径相对规格的根。
 */
export function specIssues(
  spec: DraftSpec,
  context: SpecContext,
): { path: (string | number)[]; message: string }[] {
  const issues: { path: (string | number)[]; message: string }[] = [];
  const seenMcp = new Set<string>();
  spec.mcp_servers.forEach((ref, i) => {
    if (seenMcp.has(ref.mcp_server_id))
      issues.push({ path: ['mcp_servers', i, 'mcp_server_id'], message: '同一个 MCP 服务只能挂载一次。' });
    seenMcp.add(ref.mcp_server_id);
  });
  if (spec.subagents.length > 0 && !spec.tools.includes('spawn_subagent'))
    issues.push({ path: ['tools'], message: '声明了子智能体，需要启用"拉起子智能体"（spawn_subagent）。' });
  const parent = parentToolSet(spec, context);
  const names = new Set<string>();
  spec.subagents.forEach((sub, i) => {
    if (names.has(sub.name))
      issues.push({ path: ['subagents', i, 'name'], message: `子智能体名 ${sub.name} 重复了。` });
    names.add(sub.name);
    const extra = sub.tools.filter((t) => !parent.has(t));
    if (extra.length > 0)
      issues.push({
        path: ['subagents', i, 'tools'],
        message: `${extra.join('、')} 不在本智能体的工具中，子智能体只能用父智能体启用的工具。`,
      });
    const seconds = durationSeconds(sub.timeout);
    if (!(seconds > 0 && seconds <= MAX_SUBAGENT_TIMEOUT_SECONDS))
      issues.push({
        path: ['subagents', i, 'timeout'],
        message: `超时在 1 到 ${MAX_SUBAGENT_TIMEOUT_SECONDS} 秒之间。`,
      });
  });
  if (spec.knowledge.prefetch && spec.knowledge.knowledge_base_ids.length === 0)
    issues.push({
      path: ['knowledge', 'knowledge_base_ids'],
      message: '开启了前置检索，至少绑定一个知识库。',
    });
  const subagentNames = spec.subagents.map((s) => s.name);
  spec.permissions.rules.forEach((rule, i) => {
    const problem = checkRuleContent(
      rule.tool_name,
      rule.rule_content,
      spec.knowledge.knowledge_base_ids,
      subagentNames,
    );
    if (problem)
      issues.push({
        path: ['permissions', 'rules', i, 'rule_content'],
        message: `规则 ${rule.tool_name}(${rule.rule_content ?? ''})：${problem}`,
      });
  });
  return issues;
}

/** 带跨字段规则的草稿规格 schema。 */
export function specSchema(context: SpecContext) {
  return draftSpecSchema.superRefine((spec, ctx) => {
    for (const issue of specIssues(spec, context)) ctx.addIssue({ code: 'custom', ...issue });
  });
}

/** 新智能体的草稿：只有主模型，其余取 schema 说明中的默认值（单轮推理 50 次、预留 20000 token、前置检索 5 个切片）。 */
export function defaultSpec(modelId: string): DraftSpec {
  return {
    model: { model_id: modelId, fallback_model_id: null, max_output_tokens: null, temperature: null },
    system_prompt: '',
    tools: [],
    mcp_servers: [],
    skills: [],
    subagents: [],
    permissions: { mode: 'default', rules: [] },
    compaction: { reserved_tokens: 20_000 },
    plan_mode: false,
    knowledge: { knowledge_base_ids: [], prefetch: false, prefetch_top_k: 5, query_rewrite_model: null },
    max_iters: 50,
    expose_thinking: false,
  };
}

/** 新的子智能体：继承主模型，超时 5 分钟。 */
export function defaultSubagent(name: string, modelId: string): SubagentSpec {
  return {
    name,
    description: '',
    model: { model_id: modelId, fallback_model_id: null, max_output_tokens: null, temperature: null },
    system_prompt: '',
    tools: [],
    skills: [],
    permissions: { mode: 'dont_ask', rules: [] },
    max_iters: 20,
    timeout: secondsDuration(300),
  };
}

type Json = Record<string, unknown>;

const asObject = (value: unknown): Json =>
  typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Json) : {};
const asArray = (value: unknown): unknown[] => (Array.isArray(value) ? value : []);
const nullable = <T>(value: unknown, guard: (v: unknown) => v is T): T | null =>
  guard(value) ? value : null;
const isString = (v: unknown): v is string => typeof v === 'string';
const isNumber = (v: unknown): v is number => typeof v === 'number';
const text = (v: unknown): string => (typeof v === 'string' ? v : '');

function modelFrom(value: unknown): ModelRef {
  const m = asObject(value);
  return {
    model_id: isString(m['model_id']) ? m['model_id'] : '',
    fallback_model_id: nullable(m['fallback_model_id'], isString),
    max_output_tokens: nullable(m['max_output_tokens'], isNumber),
    temperature: nullable(m['temperature'], isNumber),
  };
}

function policyFrom(value: unknown): PermissionPolicy {
  const p = asObject(value);
  return {
    mode: (p['mode'] as PermissionMode | undefined) ?? 'default',
    rules: asArray(p['rules']).map((r) => {
      const rule = asObject(r);
      return {
        tool_name: text(rule['tool_name']),
        rule_content: nullable(rule['rule_content'], isString),
        behavior: (rule['behavior'] as RuleBehavior | undefined) ?? 'allow',
        layer: 'agent_version' as const,
      };
    }),
  };
}

const skillsFrom = (value: unknown): SkillRef[] =>
  asArray(value).map((s) => {
    const ref = asObject(s);
    return { skill_id: text(ref['skill_id']), version: Number(ref['version'] ?? 1) };
  });

/**
 * 把接口返回的规格（`AgentVersion.spec`，结构由 JSON Schema 约束，生成类型是自由对象）读成表单值：去掉发布时写入的快照字段，
 * 补齐可省略字段的默认值。已发布版本的规格同样适用（基于它创建新草稿）。
 */
export function toDraftSpec(raw: Readonly<Record<string, unknown>>): DraftSpec {
  const fallback = defaultSpec('');
  const knowledge = asObject(raw['knowledge']);
  return {
    model: modelFrom(raw['model']),
    system_prompt: isString(raw['system_prompt']) ? raw['system_prompt'] : '',
    tools: asArray(raw['tools']).filter(isString).filter(isPlatformTool),
    mcp_servers: asArray(raw['mcp_servers']).map((s) => {
      const ref = asObject(s);
      return {
        mcp_server_id: text(ref['mcp_server_id']),
        enabled_tools: asArray(ref['enabled_tools']).filter(isString),
      };
    }),
    skills: skillsFrom(raw['skills']),
    subagents: asArray(raw['subagents']).map((s) => {
      const sub = asObject(s);
      return {
        name: text(sub['name']),
        description: text(sub['description']),
        model: modelFrom(sub['model']),
        system_prompt: isString(sub['system_prompt']) ? sub['system_prompt'] : '',
        tools: asArray(sub['tools']).filter(isString),
        skills: skillsFrom(sub['skills']),
        permissions: policyFrom(sub['permissions']),
        max_iters: isNumber(sub['max_iters']) ? sub['max_iters'] : 20,
        timeout: isString(sub['timeout']) ? sub['timeout'] : secondsDuration(300),
      };
    }),
    permissions: policyFrom(raw['permissions']),
    compaction: {
      reserved_tokens: isNumber(asObject(raw['compaction'])['reserved_tokens'])
        ? (asObject(raw['compaction'])['reserved_tokens'] as number)
        : fallback.compaction.reserved_tokens,
    },
    plan_mode: raw['plan_mode'] === true,
    knowledge: {
      knowledge_base_ids: asArray(knowledge['knowledge_base_ids']).filter(isString),
      prefetch: knowledge['prefetch'] === true,
      prefetch_top_k: isNumber(knowledge['prefetch_top_k'])
        ? knowledge['prefetch_top_k']
        : fallback.knowledge.prefetch_top_k,
      query_rewrite_model: nullable(knowledge['query_rewrite_model'], isString),
    },
    max_iters: isNumber(raw['max_iters']) ? raw['max_iters'] : fallback.max_iters,
    expose_thinking: raw['expose_thinking'] === true,
  };
}

/** 子智能体可选的工具：父智能体启用的平台工具（除 spawn_subagent）与挂载的 MCP 工具全名。 */
export function subagentToolChoices(
  spec: Pick<DraftSpec, 'tools' | 'mcp_servers'>,
  mcpTools: ReadonlyMap<string, { name: string; tools: readonly string[] }>,
): string[] {
  const choices: string[] = spec.tools.filter((t) => t !== 'spawn_subagent');
  for (const ref of spec.mcp_servers) {
    const server = mcpTools.get(ref.mcp_server_id);
    if (!server) continue;
    const enabled = ref.enabled_tools.length === 0 ? server.tools : ref.enabled_tools;
    choices.push(...enabled.map((t) => mcpToolName(server.name, t)));
  }
  return choices;
}
