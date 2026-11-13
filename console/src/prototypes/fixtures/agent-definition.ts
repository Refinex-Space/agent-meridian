/**
 * 智能体编辑与详情的视图模型。正式数据来自管理 API：草稿规格 `GET P:/agents/{id}/versions/{v}`（结构由
 * `agent-spec.schema.json` 约束），模型、知识库、技能、MCP 服务的可选项来自各自的列表接口；T22 接入时把接口结果映射成这些结构，
 * 字段名改为驼峰、ID 换成显示名所需的对象。这里不是 API 类型。
 */
import {
  AGENT_CONTRACT,
  AGENT_POLICY,
  AGENT_SERVICE_QA,
  AGENT_STORE_PATROL,
  AGENT_WEEKLY,
  PROJECT_AGENTS,
} from '@mocks/fixtures/agents';
import { Q3_AGENT } from '@mocks/fixtures/q3-session';

export type PlatformTool =
  | 'read_file'
  | 'list_files'
  | 'glob_files'
  | 'grep_files'
  | 'write_file'
  | 'edit_file'
  | 'bash'
  | 'web_search'
  | 'web_fetch'
  | 'knowledge_search'
  | 'spawn_subagent';

export type PermissionMode = 'default' | 'accept_edits' | 'explore' | 'bypass' | 'dont_ask';
export type RuleBehavior = 'allow' | 'ask' | 'deny';

export interface PermissionRuleForm {
  key: string;
  toolName: string;
  /** 规则内容（命令前缀、路径 glob、域名）；null 表示整个工具。 */
  ruleContent: string | null;
  behavior: RuleBehavior;
}

export interface SubagentForm {
  name: string;
  description: string;
  modelId: string;
  tools: string[];
  maxIters: number;
  timeoutSeconds: number;
}

export interface AgentDefinitionForm {
  name: string;
  description: string;
  modelId: string;
  /** null：使用模型目录给主模型配置的备用模型。 */
  fallbackModelId: string | null;
  maxOutputTokens: number | null;
  temperature: number | null;
  systemPrompt: string;
  tools: PlatformTool[];
  permissionMode: PermissionMode;
  rules: PermissionRuleForm[];
  knowledgeBaseIds: string[];
  prefetch: boolean;
  prefetchTopK: number;
  queryRewriteModelId: string | null;
  skills: { skillId: string; version: number }[];
  mcpServers: { mcpServerId: string; enabledTools: string[] }[];
  subagents: SubagentForm[];
  maxIters: number;
  reservedTokens: number;
  planMode: boolean;
  exposeThinking: boolean;
}

export interface ModelOption {
  id: string;
  name: string;
  provider: string;
  /** 上下文窗口（token）。 */
  contextWindow: number;
  defaultMaxOutput: number;
}

export const MODELS: readonly ModelOption[] = [
  {
    id: 'mdl_01JBW2QP7K3M9X4V1R6T8Y2ZAB',
    name: 'qwen-plus',
    provider: '阿里云百炼',
    contextWindow: 131_072,
    defaultMaxOutput: 8_192,
  },
  {
    id: 'mdl_01JBW2QF5H8N2Q6S9V3X7Z1BCD',
    name: 'qwen3.7-flash',
    provider: '阿里云百炼',
    contextWindow: 131_072,
    defaultMaxOutput: 8_192,
  },
  {
    id: 'mdl_01JBW2R3T7W1Y5A9C3E7G1J5LN',
    name: 'qwen-max',
    provider: '阿里云百炼',
    contextWindow: 32_768,
    defaultMaxOutput: 8_192,
  },
  {
    id: 'mdl_01JBW2S6V0X4Z8B2D6F0H4K8MP',
    name: 'deepseek-v3.2',
    provider: '深度求索',
    contextWindow: 131_072,
    defaultMaxOutput: 8_192,
  },
];

export const MODEL_NAME: Readonly<Record<string, string>> = Object.fromEntries(
  MODELS.map((m) => [m.id, m.name]),
);

export const PLATFORM_TOOLS: readonly { name: PlatformTool; label: string; hint?: string }[] = [
  { name: 'read_file', label: '读文件' },
  { name: 'list_files', label: '列目录' },
  { name: 'glob_files', label: '按模式找文件' },
  { name: 'grep_files', label: '搜索文件内容' },
  { name: 'write_file', label: '写文件' },
  { name: 'edit_file', label: '改文件' },
  { name: 'bash', label: '执行命令', hint: '只在沙箱档的环境中生效' },
  { name: 'web_search', label: '联网搜索' },
  { name: 'web_fetch', label: '读取网页' },
  { name: 'knowledge_search', label: '检索知识库', hint: '只能检索下面绑定的知识库' },
  { name: 'spawn_subagent', label: '拉起子智能体', hint: '声明了子智能体时必须启用' },
];

export const PERMISSION_MODES: readonly { value: PermissionMode; label: string; hint: string }[] = [
  { value: 'default', label: '默认', hint: '没有命中放行规则的调用都要人工确认' },
  { value: 'accept_edits', label: '自动接受编辑', hint: '写文件、改文件不再确认，其余同默认' },
  { value: 'explore', label: '只读探索', hint: '只允许读类工具' },
  { value: 'bypass', label: '跳过确认', hint: '除拒绝规则外全部放行，只用于受信任的内部任务' },
  { value: 'dont_ask', label: '不询问', hint: '没有命中放行规则的调用直接拒绝，用于无人值守' },
];

export const KNOWLEDGE_BASES: readonly { id: string; name: string; documents: number }[] = [
  { id: 'kb_01JBY3M8Q2T6W0Y4B8D2F6H0KN', name: '季度经营月报', documents: 36 },
  { id: 'kb_01JBY4P1S5V9X3Z7B1D5F9H3KQ', name: '华东区促销政策', documents: 112 },
  { id: 'kb_01JBY5R4T8W2Y6A0C4E8G2J6LS', name: '门店运营手册', documents: 58 },
  { id: 'kb_01JBY6T7V1X5Z9B3D7F1H5K9MV', name: '费用报销制度', documents: 21 },
];

export const SKILLS: readonly { id: string; name: string; versions: number[] }[] = [
  { id: 'skl_01JBZ1A4C8E2G6J0L4N8Q2S6UW', name: 'excel-report', versions: [1, 2, 3] },
  { id: 'skl_01JBZ2B7D1F5H9K3M7P1R5T9WY', name: 'sales-metrics', versions: [1, 2] },
  { id: 'skl_01JBZ3C0E4G8J2L6N0Q4S8V2XZ', name: 'docx', versions: [1] },
];

export const MCP_SERVERS: readonly { id: string; name: string; tools: string[] }[] = [
  {
    id: 'mcp_01JBZ8F3H7K1M5P9R3T7W1Y5BC',
    name: 'erp',
    tools: ['query_sales', 'query_inventory', 'list_stores'],
  },
  {
    id: 'mcp_01JBZ9G6J0L4N8Q2S6V0X4Z8DF',
    name: 'crm',
    tools: ['get_ticket', 'update_ticket', 'search_customers'],
  },
];

const PROMPT = `你是华东大区的经营分析助手，服务对象是大区运营、品类经理和门店督导。

回答经营问题时：
1. 先确认口径：时间范围、区域、对比基准（同比、环比或目标）。口径不清楚时先问，不要猜。
2. 数据以 ERP 的销售与库存为准；经营结论、促销背景以知识库中的季度经营月报为准。两者矛盾时说明差异，不要只取一边。
3. 涉及多个品类或门店的计算，交给 data-analyst 子智能体；涉及促销与费用政策的解读，交给 policy-reader 子智能体。
4. 结论先行：第一段给出结论与关键数字，再分点说明原因。金额用万元或亿元，保留两位小数；百分比保留一位小数。
5. 需要导出明细时，用 scripts/ 下已有的脚本生成 Excel，放在工作区的 reports/ 目录，回复中给出文件路径。

不要做的事：
- 不估算 ERP 中没有的数据，不编造门店或品类名称。
- 不对单个员工做绩效评价。
- 不把客户手机号、会员卡号等个人信息写进回复或导出文件。`;

/** 经营分析助手 v13 的草稿（基于已发布的 v12）。 */
export const Q3_AGENT_DRAFT: AgentDefinitionForm = {
  name: Q3_AGENT.name,
  description: Q3_AGENT.description,
  modelId: 'mdl_01JBW2QP7K3M9X4V1R6T8Y2ZAB',
  fallbackModelId: null,
  maxOutputTokens: null,
  temperature: 0.3,
  systemPrompt: PROMPT,
  tools: [
    'read_file',
    'list_files',
    'glob_files',
    'grep_files',
    'write_file',
    'bash',
    'knowledge_search',
    'spawn_subagent',
  ],
  permissionMode: 'default',
  rules: [
    { key: 'r1', toolName: 'read_file', ruleContent: null, behavior: 'allow' },
    { key: 'r2', toolName: 'list_files', ruleContent: null, behavior: 'allow' },
    { key: 'r3', toolName: 'glob_files', ruleContent: null, behavior: 'allow' },
    { key: 'r4', toolName: 'grep_files', ruleContent: null, behavior: 'allow' },
    { key: 'r5', toolName: 'knowledge_search', ruleContent: null, behavior: 'allow' },
    { key: 'r6', toolName: 'spawn_subagent', ruleContent: null, behavior: 'allow' },
    { key: 'r7', toolName: 'write_file', ruleContent: 'reports/**', behavior: 'allow' },
    { key: 'r8', toolName: 'bash', ruleContent: 'python scripts/yoy_by_category.py:*', behavior: 'allow' },
    { key: 'r9', toolName: 'bash', ruleContent: 'rm:*', behavior: 'deny' },
    { key: 'r10', toolName: 'mcp__erp__query_sales', ruleContent: null, behavior: 'allow' },
  ],
  knowledgeBaseIds: ['kb_01JBY3M8Q2T6W0Y4B8D2F6H0KN', 'kb_01JBY4P1S5V9X3Z7B1D5F9H3KQ'],
  prefetch: true,
  prefetchTopK: 5,
  queryRewriteModelId: 'mdl_01JBW2QF5H8N2Q6S9V3X7Z1BCD',
  skills: [
    { skillId: 'skl_01JBZ1A4C8E2G6J0L4N8Q2S6UW', version: 3 },
    { skillId: 'skl_01JBZ2B7D1F5H9K3M7P1R5T9WY', version: 2 },
  ],
  mcpServers: [
    { mcpServerId: 'mcp_01JBZ8F3H7K1M5P9R3T7W1Y5BC', enabledTools: ['query_sales', 'query_inventory'] },
  ],
  subagents: [
    {
      name: 'data-analyst',
      description: '按品类、门店计算同比环比与贡献度，输出表格',
      modelId: 'mdl_01JBW2QF5H8N2Q6S9V3X7Z1BCD',
      tools: ['read_file', 'bash', 'mcp__erp__query_sales'],
      maxIters: 30,
      timeoutSeconds: 300,
    },
    {
      name: 'policy-reader',
      description: '查促销与费用政策，给出条款原文与出处',
      modelId: 'mdl_01JBW2QF5H8N2Q6S9V3X7Z1BCD',
      tools: ['knowledge_search', 'read_file'],
      maxIters: 20,
      timeoutSeconds: 180,
    },
  ],
  maxIters: 50,
  reservedTokens: 20_000,
  planMode: false,
  exposeThinking: true,
};

/** 草稿与版本的元信息（页头）。 */
export interface DraftMeta {
  agentId: string;
  draftVersion: number;
  baseVersion: number;
  savedAt: string;
  /** 使用中的会话数（`GET /v1/session-stats?agent_id=` 的 `active_sessions`）：发布后它们仍用旧版本。 */
  activeSessions: number;
}

export const Q3_DRAFT_META: DraftMeta = {
  agentId: Q3_AGENT.id,
  draftVersion: 13,
  baseVersion: 12,
  savedAt: '2026-11-13T02:42:00.000Z',
  activeSessions: 7,
};

/** 智能体列表与详情的视图模型：智能体加上运行情况（`GET /v1/session-stats` 的 `agents[]`）。 */
export interface AgentRow {
  id: string;
  name: string;
  description: string;
  version: number;
  publishedAt: string;
  updatedAt: string;
  /** 有未发布的草稿。 */
  hasDraft: boolean;
  activeSessions: number;
  runningSessions: number;
  /** 24 小时内的失败率；没有轮次时为 null。 */
  failureRate: number | null;
  modelName: string;
}

/** 每个智能体的运行情况与当前版本的主模型：使用中、运行中的会话数，24 小时失败率，是否有草稿。 */
const RUN: Record<
  string,
  { active: number; running: number; rate: number | null; draft: boolean; model: string }
> = {
  [Q3_AGENT.id]: { active: 7, running: 2, rate: 3 / 86, draft: true, model: 'qwen-plus' },
  [AGENT_SERVICE_QA.id]: { active: 12, running: 3, rate: 2 / 412, draft: false, model: 'qwen3.7-flash' },
  [AGENT_POLICY.id]: { active: 4, running: 1, rate: 0, draft: false, model: 'qwen-plus' },
  [AGENT_STORE_PATROL.id]: { active: 5, running: 1, rate: 9 / 64, draft: true, model: 'qwen-max' },
  [AGENT_CONTRACT.id]: { active: 2, running: 0, rate: 1 / 21, draft: false, model: 'deepseek-v3.2' },
  [AGENT_WEEKLY.id]: { active: 0, running: 0, rate: 0, draft: false, model: 'qwen-plus' },
};

export const AGENT_ROWS: readonly AgentRow[] = PROJECT_AGENTS.map((a) => {
  const run = RUN[a.id] ?? { active: 0, running: 0, rate: null, draft: false, model: '' };
  return {
    id: a.id,
    name: a.name,
    description: a.description,
    version: a.current_version.version,
    publishedAt: a.current_version.published_at,
    updatedAt: a.updated_at,
    hasDraft: run.draft,
    activeSessions: run.active,
    runningSessions: run.running,
    failureRate: run.rate,
    modelName: run.model,
  };
});

/** 一个版本（`GET P:/agents/{id}/versions` 的一项，加上发布人的显示名）。 */
export interface AgentVersionRow {
  version: number;
  state: 'draft' | 'current' | 'previous';
  /** 发布时间；草稿为最近保存的时间。 */
  at: string;
  author: string;
  /** 发布说明。 */
  note: string;
}

export const Q3_VERSIONS: readonly AgentVersionRow[] = [
  {
    version: 13,
    state: 'draft',
    at: '2026-11-13T02:42:00.000Z',
    author: '林晓雯',
    note: '温度从 0.5 降到 0.3；子智能体改用 qwen3.7-flash',
  },
  {
    version: 12,
    state: 'current',
    at: '2026-11-10T08:40:00.000Z',
    author: '林晓雯',
    note: '导出改用 scripts/export_decline.py，放行 reports/ 下的写入',
  },
  {
    version: 11,
    state: 'previous',
    at: '2026-11-04T06:12:00.000Z',
    author: '周启航',
    note: '绑定华东区促销政策知识库，开启前置检索',
  },
  {
    version: 10,
    state: 'previous',
    at: '2026-10-28T09:55:00.000Z',
    author: '林晓雯',
    note: '新增 policy-reader 子智能体',
  },
  {
    version: 9,
    state: 'previous',
    at: '2026-10-21T03:30:00.000Z',
    author: '林晓雯',
    note: '系统提示词补充口径确认与金额单位',
  },
  {
    version: 8,
    state: 'previous',
    at: '2026-10-15T07:02:00.000Z',
    author: '周启航',
    note: '挂载 ERP 的 MCP 服务，只启用查询类工具',
  },
];
