/**
 * 字段的"锚点"：一条错误（本地 Zod 的路径，或服务端 `errors[].field` 的点号路径）显示在哪个字段下、属于哪个表单分组、
 * 控件的 DOM id 是什么。本地校验与服务端校验走同一套映射，服务端的错误也能定位到字段（T22-A02）。
 *
 * 表单值是 `{ name, description, spec }`：`name`、`description` 是智能体资源的字段（PATCH 智能体），`spec` 是草稿规格
 * （PUT 版本），服务端的字段名同样以 `name` 或 `spec.` 开头。
 */

/** 编辑页的分组，顺序即左侧导航的顺序。 */
export const AGENT_GROUPS = [
  { id: 'basic', label: '基本信息' },
  { id: 'model', label: '模型' },
  { id: 'prompt', label: '提示词' },
  { id: 'tools', label: '工具' },
  { id: 'permissions', label: '权限' },
  { id: 'knowledge', label: '知识库' },
  { id: 'skills', label: '技能' },
  { id: 'subagents', label: '子智能体' },
  { id: 'runtime', label: '上下文与计划' },
] as const;

export type AgentGroupId = (typeof AGENT_GROUPS)[number]['id'];

/** 子智能体中显示错误的字段（其余落到子智能体本身）。 */
const SUBAGENT_FIELDS = new Set([
  'name',
  'description',
  'system_prompt',
  'tools',
  'skills',
  'max_iters',
  'timeout',
]);
const MODEL_FIELDS = new Set(['model_id', 'fallback_model_id', 'max_output_tokens', 'temperature']);

/** 表单级的错误（找不到字段时）显示在保存栏上方。 */
export const ROOT_ANCHOR = 'root';

/**
 * 把错误路径映射到显示它的字段。数组下标是数字段：`spec.subagents.0.tools.1` → `spec.subagents.0.tools`。
 */
export function anchorOf(path: readonly (string | number)[]): string {
  const [head, ...rest] = path.map(String);
  if (head === 'name' || head === 'description') return head;
  if (head !== 'spec' || rest.length === 0) return ROOT_ANCHOR;
  const [top, second, third, fourth] = rest;
  const join = (...parts: (string | undefined)[]) =>
    ['spec', ...parts.filter((p) => p !== undefined)].join('.');
  switch (top) {
    case 'model':
      return second && MODEL_FIELDS.has(second) ? join('model', second) : join('model', 'model_id');
    case 'system_prompt':
    case 'tools':
    case 'skills':
    case 'plan_mode':
    case 'max_iters':
    case 'expose_thinking':
      return join(top);
    case 'mcp_servers':
      return second !== undefined ? join('mcp_servers', second) : join('mcp_servers');
    case 'permissions':
      return second === 'mode' ? join('permissions', 'mode') : join('permissions', 'rules');
    case 'compaction':
      return join('compaction', 'reserved_tokens');
    case 'knowledge':
      return second === 'prefetch_top_k' || second === 'query_rewrite_model' || second === 'prefetch'
        ? join('knowledge', second)
        : join('knowledge', 'knowledge_base_ids');
    case 'subagents': {
      if (second === undefined) return join('subagents');
      if (third === 'model')
        return join('subagents', second, 'model', fourth && MODEL_FIELDS.has(fourth) ? fourth : 'model_id');
      if (third === 'permissions') return join('subagents', second, 'permissions');
      return third && SUBAGENT_FIELDS.has(third)
        ? join('subagents', second, third)
        : join('subagents', second, 'name');
    }
    default:
      return ROOT_ANCHOR;
  }
}

/** 点号路径（服务端的 `errors[].field`）拆成段。 */
export function splitField(field: string): string[] {
  return field.split('.').filter((part) => part !== '');
}

/** 锚点所在的分组；表单级错误不属于任何分组。 */
export function groupOf(anchor: string): AgentGroupId | null {
  if (anchor === 'name' || anchor === 'description') return 'basic';
  const [, top] = anchor.split('.');
  switch (top) {
    case 'model':
      return 'model';
    case 'system_prompt':
      return 'prompt';
    case 'tools':
    case 'mcp_servers':
      return 'tools';
    case 'permissions':
      return 'permissions';
    case 'knowledge':
      return 'knowledge';
    case 'skills':
      return 'skills';
    case 'subagents':
      return 'subagents';
    case 'compaction':
    case 'plan_mode':
    case 'max_iters':
    case 'expose_thinking':
      return 'runtime';
    default:
      return null;
  }
}

/** 锚点对应控件的 DOM id（`spec.subagents.0.tools` → `field-spec-subagents-0-tools`）。 */
export function fieldId(anchor: string): string {
  return `field-${anchor.replaceAll('.', '-')}`;
}

/** 按分组顺序排列锚点：提交时滚到第一个出错的字段。 */
export function sortAnchors(anchors: Iterable<string>): string[] {
  const order = (anchor: string) => {
    const group = groupOf(anchor);
    return group === null ? AGENT_GROUPS.length : AGENT_GROUPS.findIndex((g) => g.id === group);
  };
  return [...anchors].sort((a, b) => order(a) - order(b) || a.localeCompare(b, 'en', { numeric: true }));
}
