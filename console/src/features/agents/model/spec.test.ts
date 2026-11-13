import { readFileSync } from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';
import { z } from 'zod';

import {
  defaultSpec,
  defaultSubagent,
  draftSpecSchema,
  durationSeconds,
  specIssues,
  specSchema,
  toDraftSpec,
} from './spec';

type Schema = Record<string, unknown>;

const official = JSON.parse(
  readFileSync(
    path.resolve(
      import.meta.dirname,
      '../../../../../meridian-api/src/main/resources/schema/agent-spec.schema.json',
    ),
    'utf8',
  ),
) as Schema;
const defs = official['$defs'] as Record<string, Schema>;
const ours = z.toJSONSchema(draftSpecSchema, { unrepresentable: 'any' }) as Schema;

/** "发布时填写"的快照字段：草稿可以省略，表单不编辑。 */
const SNAPSHOT: Record<string, readonly string[]> = {
  '': ['agent_id', 'agent_version_id', 'name'],
  'mcp_servers[]': ['name', 'url', 'transport', 'header_secret_ids', 'side_effect', 'tool_side_effects'],
  'skills[]': ['name'],
  'subagents[].skills[]': ['name'],
};

/** 展开 `$ref`，把 `allOf` 中只有一个分支的包装去掉。 */
function resolve(schema: Schema): Schema {
  let current = schema;
  while (typeof current['$ref'] === 'string') {
    const name = current['$ref'].replace('#/$defs/', '');
    const rest = Object.fromEntries(Object.entries(current).filter(([key]) => key !== '$ref'));
    current = { ...defs[name], ...rest };
  }
  return current;
}

/** 可空写法归一：官方的 `type: [T, "null"]` 与 `oneOf: [ref, null]`，Zod 的 `anyOf: [T, null]`。 */
function unwrapNullable(schema: Schema): { schema: Schema; nullable: boolean } {
  const s = resolve(schema);
  if (Array.isArray(s['type']) && (s['type'] as string[]).includes('null')) {
    const type = (s['type'] as string[]).filter((t) => t !== 'null');
    return { schema: { ...s, type: type[0] }, nullable: true };
  }
  const union = (s['anyOf'] ?? s['oneOf']) as Schema[] | undefined;
  if (union && union.length === 2 && union.some((u) => u['type'] === 'null')) {
    const other = union.find((u) => u['type'] !== 'null')!;
    return { schema: resolve(other), nullable: true };
  }
  return { schema: s, nullable: false };
}

const KEYWORDS = [
  'type',
  'enum',
  'const',
  'minimum',
  'maxLength',
  'minLength',
  'pattern',
  'maxItems',
] as const;

/** 逐字段比对官方 schema 与表单 schema，返回不一致之处。 */
function compare(theirs: Schema, mine: Schema, at: string, out: string[]): void {
  const a = unwrapNullable(theirs);
  const b = unwrapNullable(mine);
  if (a.nullable !== b.nullable) out.push(`${at}: 可空不一致`);
  const t = a.schema;
  const m = b.schema;
  // 子智能体的工具（平台工具除 spawn_subagent，或 MCP 工具全名）在 Zod 中是 refine，按行为测试。
  if (t['anyOf'] && !a.nullable) return;
  for (const key of KEYWORDS) {
    if (t[key] === undefined) continue;
    if (JSON.stringify(t[key]) !== JSON.stringify(m[key]))
      out.push(`${at}: ${key} 官方 ${JSON.stringify(t[key])}，表单 ${JSON.stringify(m[key])}`);
  }
  // Zod 对没有上限的整数给出 Number.MAX_SAFE_INTEGER。
  if (
    t['maximum'] !== m['maximum'] &&
    !(t['maximum'] === undefined && m['maximum'] === Number.MAX_SAFE_INTEGER)
  )
    out.push(`${at}: maximum 官方 ${String(t['maximum'])}，表单 ${String(m['maximum'])}`);
  if (t['type'] === 'object') {
    if (m['additionalProperties'] !== false) out.push(`${at}: 表单没有拒绝多余字段`);
    const skip = SNAPSHOT[at] ?? [];
    const theirProps = Object.keys((t['properties'] as Schema) ?? {}).filter((k) => !skip.includes(k));
    const myProps = Object.keys((m['properties'] as Schema) ?? {});
    if (JSON.stringify([...theirProps].sort()) !== JSON.stringify([...myProps].sort()))
      out.push(`${at}: 字段 官方 ${theirProps.join(',')}，表单 ${myProps.join(',')}`);
    for (const required of (t['required'] as string[] | undefined) ?? [])
      if (!skip.includes(required) && !((m['required'] as string[]) ?? []).includes(required))
        out.push(`${at}: 表单没有要求 ${required}`);
    for (const key of theirProps) {
      const child = (t['properties'] as Record<string, Schema>)[key]!;
      const mineChild = (m['properties'] as Record<string, Schema>)[key];
      if (mineChild) compare(child, mineChild, at ? `${at}.${key}` : key, out);
    }
  }
  if (t['type'] === 'array' && t['items'])
    compare(t['items'] as Schema, m['items'] as Schema, `${at || ''}[]`.replace(/^\./, ''), out);
}

describe('草稿规格的 schema 与 agent-spec.schema.json 一致', () => {
  it('字段、类型、取值范围、格式与枚举逐项相同（快照字段除外）', () => {
    const problems: string[] = [];
    compare(official, ours, '', problems);
    expect(problems).toEqual([]);
  });

  it('比对能发现不一致：上限、枚举与多出的字段', () => {
    const drifted = draftSpecSchema.extend({
      max_iters: z.number().int().min(1).max(300),
      plan_mode: z.enum(['on', 'off']),
      extra: z.string(),
    });
    const problems: string[] = [];
    compare(official, z.toJSONSchema(drifted, { unrepresentable: 'any' }), '', problems);
    expect(problems.join('\n')).toMatch(/字段 官方/);
    expect(problems.join('\n')).toMatch(/max_iters: maximum 官方 200，表单 300/);
    expect(problems.join('\n')).toMatch(/plan_mode: type 官方 "boolean"/);
  });

  it('官方 schema 的示例规格（去掉快照字段）通过表单校验', () => {
    const spec = defaultSpec('mdl_01JC8Z4E7G9J1L3N5Q7S9U1W3Y');
    expect(draftSpecSchema.safeParse(spec).success).toBe(true);
  });

  it('子智能体的工具：平台工具（不含 spawn_subagent）或 MCP 工具全名', () => {
    const sub = { ...defaultSubagent('reader', 'mdl_a'), model: { ...defaultSubagent('r', 'mdl_a').model } };
    const tools = z.array(draftSpecSchema.shape.subagents.element.shape.tools.element);
    expect(tools.safeParse(['read_file', 'mcp__crm__lookup_order']).success).toBe(true);
    expect(tools.safeParse(['spawn_subagent']).success).toBe(false);
    expect(tools.safeParse(['rm_rf']).success).toBe(false);
    expect(sub.timeout).toBe('PT300S');
  });

  it('工具、知识库、启用的 MCP 工具不能重复', () => {
    const spec = { ...defaultSpec('mdl_a'), tools: ['read_file', 'read_file'] };
    const result = draftSpecSchema.safeParse(spec);
    expect(result.success).toBe(false);
    expect(result.error?.issues[0]?.path).toEqual(['tools']);
  });
});

describe('发布时的跨字段规则', () => {
  const context = { mcpServerNames: new Map([['mcp_crm', 'crm']]) };

  it('声明了子智能体时要启用 spawn_subagent', () => {
    const spec = { ...defaultSpec('mdl_a'), subagents: [defaultSubagent('reader', 'mdl_a')] };
    const issue = specIssues(spec, context).find((i) => i.path[0] === 'tools');
    expect(issue?.message).toContain('spawn_subagent');
  });

  it('子智能体的工具超出父智能体时在本地拦截（T22-A02）', () => {
    const spec = {
      ...defaultSpec('mdl_a'),
      tools: ['read_file', 'spawn_subagent'] as const,
      mcp_servers: [{ mcp_server_id: 'mcp_crm', enabled_tools: ['lookup_order'] }],
      subagents: [
        {
          ...defaultSubagent('reader', 'mdl_a'),
          tools: ['read_file', 'web_fetch', 'mcp__crm__lookup_order', 'mcp__crm__refund'],
        },
      ],
    };
    const issues = specIssues({ ...spec, tools: [...spec.tools] }, context);
    expect(issues).toEqual([
      {
        path: ['subagents', 0, 'tools'],
        message: 'web_fetch、mcp__crm__refund 不在本智能体的工具中，子智能体只能用父智能体启用的工具。',
      },
    ]);
  });

  it('MCP 服务启用全部工具（空清单）时，子智能体可以用它的任何工具', () => {
    const spec = {
      ...defaultSpec('mdl_a'),
      tools: ['spawn_subagent' as const],
      mcp_servers: [{ mcp_server_id: 'mcp_crm', enabled_tools: [] }],
      subagents: [{ ...defaultSubagent('reader', 'mdl_a'), tools: ['mcp__crm__anything'] }],
    };
    expect(specIssues(spec, context)).toEqual([]);
  });

  it('子智能体名不重复、超时在 (0, 600 秒]', () => {
    const spec = {
      ...defaultSpec('mdl_a'),
      tools: ['spawn_subagent' as const],
      subagents: [
        defaultSubagent('reader', 'mdl_a'),
        { ...defaultSubagent('reader', 'mdl_a'), timeout: 'PT11M' },
      ],
    };
    expect(specIssues(spec, context).map((i) => i.path)).toEqual([
      ['subagents', 1, 'name'],
      ['subagents', 1, 'timeout'],
    ]);
  });

  it('开启前置检索时至少绑定一个知识库', () => {
    const spec = {
      ...defaultSpec('mdl_a'),
      knowledge: { ...defaultSpec('mdl_a').knowledge, prefetch: true },
    };
    expect(specIssues(spec, context)[0]?.path).toEqual(['knowledge', 'knowledge_base_ids']);
  });

  it('权限规则的内容按工具的语法检查', () => {
    const rule = (tool_name: string, rule_content: string | null) => ({
      tool_name,
      rule_content,
      behavior: 'allow' as const,
      layer: 'agent_version' as const,
    });
    const spec = {
      ...defaultSpec('mdl_a'),
      permissions: {
        mode: 'default' as const,
        rules: [
          rule('read_file', 'reports/**'),
          rule('read_file', '/etc/passwd'),
          rule('bash', 'git status:*'),
          rule('web_fetch', 'docs.example.com'),
          rule('web_fetch', 'domain:docs.example.com'),
          rule('web_search', 'anything'),
          rule('knowledge_search', 'kb_missing'),
          rule('mcp__crm__lookup_order', null),
        ],
      },
    };
    expect(specIssues(spec, context).map((i) => i.path[2])).toEqual([1, 3, 5, 6]);
  });

  it('specSchema 把结构错误与跨字段规则合在一次校验结果里', () => {
    const spec = { ...defaultSpec('mdl_a'), subagents: [defaultSubagent('reader', 'mdl_a')] };
    const result = specSchema(context).safeParse(spec);
    expect(result.success).toBe(false);
    // 新的子智能体还没写用途（结构），也没有启用 spawn_subagent（跨字段）。
    expect(result.error?.issues.map((i) => i.path)).toEqual([['subagents', 0, 'description'], ['tools']]);
  });
});

describe('读入接口返回的规格', () => {
  it('去掉快照字段，补齐省略的可选字段', () => {
    const spec = toDraftSpec({
      agent_id: 'agt_1',
      agent_version_id: 'agv_1',
      name: 'assistant',
      model: { model_id: 'mdl_a' },
      system_prompt: '你好',
      tools: ['read_file'],
      mcp_servers: [{ mcp_server_id: 'mcp_crm', name: 'crm', url: 'https://x', enabled_tools: [] }],
      skills: [{ skill_id: 'skl_1', version: 2, name: 'docx' }],
      subagents: [],
      permissions: { mode: 'default', rules: [] },
      compaction: { reserved_tokens: 20000 },
      plan_mode: false,
      knowledge: { knowledge_base_ids: [], prefetch: false, prefetch_top_k: 5 },
      max_iters: 50,
      expose_thinking: false,
    });
    expect(spec.model).toEqual({
      model_id: 'mdl_a',
      fallback_model_id: null,
      max_output_tokens: null,
      temperature: null,
    });
    expect(spec.mcp_servers).toEqual([{ mcp_server_id: 'mcp_crm', enabled_tools: [] }]);
    expect(spec.skills).toEqual([{ skill_id: 'skl_1', version: 2 }]);
    expect(spec.knowledge.query_rewrite_model).toBeNull();
    expect(draftSpecSchema.safeParse(spec).success).toBe(true);
  });

  it('ISO 8601 时长换算成秒', () => {
    expect(durationSeconds('PT5M')).toBe(300);
    expect(durationSeconds('PT1H')).toBe(3600);
    expect(durationSeconds('PT90.5S')).toBe(90.5);
    expect(durationSeconds('5m')).toBeNaN();
  });
});
