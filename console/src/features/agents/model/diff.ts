/**
 * 版本差异的呈现：服务端的 `versions:compare` 按 JSON 指针给出新增、删除、修改（数组按位置比较），这里把它整理成给人看的行：
 * 路径换成用户的概念（"子智能体 reader 的可用工具"），模型、知识库等 ID 换成名字，字符串列表按位置的变化合成"新增、移除"，
 * 系统提示词按行比较。草稿参与对比时，发布时才写入的快照字段（端点、技能名等）不参与。
 */
import type { SpecChange } from '@/lib/api/types';

import { BEHAVIOR_LABEL } from './labels';

export interface ListDelta {
  added: string[];
  removed: string[];
}

export interface DiffLine {
  kind: 'same' | 'added' | 'removed';
  text: string;
}

export interface DiffRow {
  key: string;
  label: string;
  /** JSON 指针（行标题旁的等宽小字，便于对照原始 JSON）。 */
  pointer: string;
  kind: SpecChange['kind'];
  before: string | null;
  after: string | null;
  /** 字符串列表的增减（平台工具、知识库、启用的 MCP 工具、子智能体的工具）。 */
  list?: ListDelta;
  /** 多行文本（系统提示词）的逐行差异。 */
  lines?: DiffLine[];
}

type Spec = Readonly<Record<string, unknown>>;

/** 按 JSON 指针取值。 */
export function valueAt(spec: unknown, pointer: string): unknown {
  return pointer
    .split('/')
    .slice(1)
    .map((p) => p.replaceAll('~1', '/').replaceAll('~0', '~'))
    .reduce<unknown>((value, key) => (value as Record<string, unknown> | undefined)?.[key], spec);
}

const SNAPSHOT = [
  /^\/(agent_id|agent_version_id|name)$/,
  /^\/mcp_servers\/\d+\/(name|url|transport|header_secret_ids|side_effect|tool_side_effects)(\/|$)/,
  /^\/skills\/\d+\/name$/,
  /^\/subagents\/\d+\/skills\/\d+\/name$/,
];

/** 发布时写入的快照字段。 */
export function isSnapshotPath(pointer: string): boolean {
  return SNAPSHOT.some((re) => re.test(pointer));
}

/** 每个版本都不同、不是配置的字段：任何对比都不显示。 */
const IDENTITY = /^\/(agent_id|agent_version_id)$/;

/** 字符串列表：数组按位置比较时会出现一串逐项的变化，合成一行。 */
const STRING_LISTS = [
  /^\/tools/,
  /^\/knowledge\/knowledge_base_ids/,
  /^\/mcp_servers\/\d+\/enabled_tools/,
  /^\/subagents\/\d+\/tools/,
];

function listRoot(pointer: string): string | null {
  for (const re of STRING_LISTS) {
    const match = re.exec(pointer);
    if (match && (pointer === match[0] || /^\/\d+$/.test(pointer.slice(match[0].length)))) return match[0];
  }
  return null;
}

const FIELD_LABEL: Record<string, string> = {
  name: '名称',
  'model/model_id': '主模型',
  'model/fallback_model_id': '备用模型',
  'model/max_output_tokens': '单次最大输出 token',
  'model/temperature': '温度',
  system_prompt: '系统提示词',
  tools: '平台工具',
  'permissions/mode': '权限模式',
  'permissions/rules': '权限规则',
  'compaction/reserved_tokens': '压缩时预留的 token',
  plan_mode: '计划模式',
  'knowledge/knowledge_base_ids': '可检索的知识库',
  'knowledge/prefetch': '前置检索',
  'knowledge/prefetch_top_k': '注入的切片数',
  'knowledge/query_rewrite_model': '查询改写模型',
  max_iters: '单轮最大推理次数',
  expose_thinking: '带出思考内容',
  mcp_servers: 'MCP 服务',
  skills: '技能',
  subagents: '子智能体',
};

const SUBAGENT_FIELD: Record<string, string> = {
  name: '名称',
  description: '用途',
  'model/model_id': '模型',
  'model/fallback_model_id': '备用模型',
  'model/max_output_tokens': '单次最大输出 token',
  'model/temperature': '温度',
  system_prompt: '系统提示词',
  tools: '可用工具',
  skills: '技能',
  permissions: '放行规则',
  'permissions/rules': '放行规则',
  'permissions/mode': '权限模式',
  max_iters: '最大推理次数',
  timeout: '超时',
};

function nameIn(base: unknown, target: unknown, pointer: string, key: string): string | null {
  const value = valueAt(target, `${pointer}/${key}`) ?? valueAt(base, `${pointer}/${key}`);
  return typeof value === 'string' ? value : null;
}

/** JSON 指针对应的字段名。 */
export function labelOf(
  pointer: string,
  base: unknown,
  target: unknown,
  names: ReadonlyMap<string, string>,
): string {
  const parts = pointer.split('/').slice(1);
  const [top, index, ...rest] = parts;
  const restKey = rest.filter((p) => !/^\d+$/.test(p)).join('/');
  if (top === 'mcp_servers' && index !== undefined) {
    const id = nameIn(base, target, `/mcp_servers/${index}`, 'mcp_server_id');
    const server = (id && names.get(id)) ?? id ?? `第 ${Number(index) + 1} 个`;
    return restKey === 'enabled_tools' ? `MCP 服务 ${server} 启用的工具` : `MCP 服务 ${server}`;
  }
  if (top === 'skills' && index !== undefined) {
    const id = nameIn(base, target, `/skills/${index}`, 'skill_id');
    return `技能 ${(id && names.get(id)) ?? id ?? Number(index) + 1}`;
  }
  if (top === 'subagents' && index !== undefined) {
    const name = nameIn(base, target, `/subagents/${index}`, 'name') ?? `第 ${Number(index) + 1} 个`;
    const field =
      rest.length === 0 ? '' : (SUBAGENT_FIELD[restKey] ?? SUBAGENT_FIELD[rest[0] ?? ''] ?? restKey);
    return field ? `子智能体 ${name} 的${field}` : `子智能体 ${name}`;
  }
  if (top === 'permissions' && index === 'rules') return '权限规则';
  const key = parts.filter((p) => !/^\d+$/.test(p)).join('/');
  return FIELD_LABEL[key] ?? FIELD_LABEL[top ?? ''] ?? key;
}

function ruleText(value: object): string {
  const rule = value as Record<string, unknown>;
  const behavior = typeof rule['behavior'] === 'string' ? rule['behavior'] : '';
  const tool = typeof rule['tool_name'] === 'string' ? rule['tool_name'] : '';
  const content = rule['rule_content'];
  return `${BEHAVIOR_LABEL[behavior] ?? behavior} ${tool}${typeof content === 'string' ? `(${content})` : ''}`;
}

/** 一个取值的显示：ID 换成名字，布尔值写开启、关闭，规则写成"放行 read_file(reports/**)"，其余对象是紧凑的 JSON。 */
export function formatValue(value: unknown, names: ReadonlyMap<string, string>): string | null {
  if (value === null || value === undefined) return null;
  if (typeof value === 'boolean') return value ? '开启' : '关闭';
  if (typeof value === 'string') return names.get(value) ?? value;
  if (typeof value === 'number') return String(value);
  if (typeof value === 'object' && !Array.isArray(value) && 'tool_name' in value && 'behavior' in value)
    return ruleText(value);
  if (Array.isArray(value) && value.every((v) => typeof v === 'string'))
    return value.length === 0 ? '（空）' : value.map((v) => names.get(v) ?? v).join('、');
  return JSON.stringify(value);
}

/** 逐行比较（最长公共子序列）。提示词至多 32000 个字符，行数在千行以内。 */
export function lineDiff(before: string, after: string): DiffLine[] {
  const a = before.split('\n');
  const b = after.split('\n');
  const n = a.length;
  const m = b.length;
  const lcs = Array.from({ length: n + 1 }, () => new Uint16Array(m + 1));
  for (let i = n - 1; i >= 0; i--)
    for (let j = m - 1; j >= 0; j--)
      lcs[i]![j] = a[i] === b[j] ? lcs[i + 1]![j + 1]! + 1 : Math.max(lcs[i + 1]![j]!, lcs[i]![j + 1]!);
  const lines: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      lines.push({ kind: 'same', text: a[i]! });
      i++;
      j++;
    } else if (lcs[i + 1]![j]! >= lcs[i]![j + 1]!) lines.push({ kind: 'removed', text: a[i++]! });
    else lines.push({ kind: 'added', text: b[j++]! });
  }
  while (i < n) lines.push({ kind: 'removed', text: a[i++]! });
  while (j < m) lines.push({ kind: 'added', text: b[j++]! });
  return lines;
}

const asStrings = (value: unknown): string[] =>
  Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : [];

export interface DescribeOptions {
  /** 草稿参与对比时为 true：快照字段不参与。 */
  hideSnapshot: boolean;
  names: ReadonlyMap<string, string>;
}

/**
 * 把服务端的差异整理成显示的行（顺序同服务端：按路径）。
 *
 * @param base 基准版本的规格
 * @param target 目标版本的规格
 */
export function describeChanges(
  changes: readonly SpecChange[],
  base: Spec,
  target: Spec,
  { hideSnapshot, names }: DescribeOptions,
): DiffRow[] {
  const rows: DiffRow[] = [];
  const listed = new Set<string>();
  for (const change of changes) {
    if (IDENTITY.test(change.path) || (hideSnapshot && isSnapshotPath(change.path))) continue;
    const root = listRoot(change.path);
    if (root) {
      if (listed.has(root)) continue;
      listed.add(root);
      const before = asStrings(valueAt(base, root));
      const after = asStrings(valueAt(target, root));
      const delta = {
        added: after.filter((v) => !before.includes(v)).map((v) => names.get(v) ?? v),
        removed: before.filter((v) => !after.includes(v)).map((v) => names.get(v) ?? v),
      };
      // 只是顺序变了：不算差异。
      if (delta.added.length === 0 && delta.removed.length === 0) continue;
      rows.push({
        key: root,
        label: labelOf(root, base, target, names),
        pointer: root,
        kind: 'changed',
        before: formatValue(before, names),
        after: formatValue(after, names),
        list: delta,
      });
      continue;
    }
    const before = change.before;
    const after = change.after;
    const multiline =
      typeof before === 'string' &&
      typeof after === 'string' &&
      (before.includes('\n') || after.includes('\n'));
    rows.push({
      key: change.path,
      label: labelOf(change.path, base, target, names),
      pointer: change.path,
      kind: change.kind,
      before: formatValue(before, names),
      after: formatValue(after, names),
      ...(multiline || change.path.endsWith('/system_prompt')
        ? {
            lines: lineDiff(typeof before === 'string' ? before : '', typeof after === 'string' ? after : ''),
          }
        : {}),
    });
  }
  return rows;
}
