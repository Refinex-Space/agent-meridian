/**
 * MCP 服务的文字与表单：传输方式、副作用等级（架构设计「平台工具·副作用等级」），以及登记表单与管理 API 请求体之间的转换。
 */
import type { McpServer, McpServerRequest, McpTransport, SideEffect } from '@/lib/api/types';

export const TRANSPORT_LABEL: Record<McpTransport, string> = {
  streamable_http: 'Streamable HTTP',
  sse: 'SSE',
};

export const SIDE_EFFECT_LABEL: Record<SideEffect, string> = {
  none: '无副作用',
  idempotent: '幂等',
  non_idempotent: '有副作用',
};

export const SIDE_EFFECTS: readonly SideEffect[] = ['none', 'idempotent', 'non_idempotent'];

/** 三个等级的含义与对崩溃恢复的影响，放在 info 提示里。 */
export const SIDE_EFFECT_HINT =
  '无副作用：只读，崩溃恢复时直接重新执行。幂等：重复执行结果相同，恢复时重新执行。有副作用：恢复时不重放，状态未知的调用会请人确认重试还是跳过。没有标注的工具按服务的默认等级，服务也没有设置时按有副作用处理。';

/** 服务名：小写字母开头，小写字母、数字、下划线，至多 32 个字符（工具名前缀 `mcp__{name}__`）。 */
export const MCP_NAME = /^[a-z][a-z0-9_]{0,31}$/;
/** 请求头名：字母、数字、短横线。 */
export const HEADER_NAME = /^[A-Za-z0-9-]{1,64}$/;

/** 登记表单的值：请求头是有序的行，便于逐行编辑。 */
export interface McpForm {
  name: string;
  transport: McpTransport;
  url: string;
  headers: { name: string; secretId: string | null }[];
  sideEffect: SideEffect | null;
}

export const EMPTY_MCP_FORM: McpForm = {
  name: '',
  transport: 'streamable_http',
  url: '',
  headers: [{ name: 'Authorization', secretId: null }],
  sideEffect: null,
};

export function toMcpForm(server: McpServer): McpForm {
  return {
    name: server.name,
    transport: server.transport,
    url: server.url,
    headers: Object.entries(server.header_secret_ids).map(([name, secretId]) => ({ name, secretId })),
    sideEffect: server.side_effect,
  };
}

/** 表单的校验：键是 `name`、`url`、`headers.N`。 */
export function validateMcpForm(form: McpForm): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!MCP_NAME.test(form.name))
    errors['name'] = '小写字母开头，只用小写字母、数字与下划线，至多 32 个字符。';
  if (!/^https?:\/\/\S+$/.test(form.url.trim())) errors['url'] = '写 http 或 https 开头的地址。';
  const seen = new Set<string>();
  form.headers.forEach((h, i) => {
    const name = h.name.trim();
    if (!name && !h.secretId) return;
    if (!HEADER_NAME.test(name)) errors[`headers.${i}`] = '请求头名只用字母、数字与短横线。';
    else if (seen.has(name.toLowerCase())) errors[`headers.${i}`] = '请求头重复。';
    else if (!h.secretId) errors[`headers.${i}`] = '选择这个请求头的凭据。';
    seen.add(name.toLowerCase());
  });
  if (form.headers.filter((h) => h.name.trim() || h.secretId).length > 8)
    errors['headers'] = '至多 8 个请求头。';
  return errors;
}

/** 请求体：空行去掉；`toolSideEffects` 沿用已有的逐个标注（修改时不丢）。 */
export function toMcpRequest(
  form: McpForm,
  toolSideEffects: Record<string, SideEffect> = {},
): McpServerRequest {
  return {
    name: form.name,
    transport: form.transport,
    url: form.url.trim(),
    header_secret_ids: Object.fromEntries(
      form.headers.filter((h) => h.name.trim() && h.secretId).map((h) => [h.name.trim(), h.secretId!]),
    ),
    side_effect: form.sideEffect,
    tool_side_effects: toolSideEffects,
  };
}

/** 一个工具实际生效的等级：单独标注的，否则服务的默认，都没有时按有副作用。 */
export function effectiveSideEffect(
  server: Pick<McpServer, 'side_effect' | 'tool_side_effects'>,
  tool: string,
) {
  return server.tool_side_effects[tool] ?? server.side_effect ?? 'non_idempotent';
}
