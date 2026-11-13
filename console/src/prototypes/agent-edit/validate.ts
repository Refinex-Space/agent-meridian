import { MCP_SERVERS, type AgentDefinitionForm } from '../fixtures/agent-definition';

/** 字段 ID 到错误；字段 ID 也是控件的 id。 */
export type FormErrors = Readonly<Record<string, string>>;

/** 每个字段属于哪个分组（导航标出出错的分组）。 */
export function groupOf(field: string): string {
  if (field === 'name' || field === 'description') return 'basic';
  if (field.startsWith('model-')) return 'model';
  if (field === 'system-prompt') return 'prompt';
  if (field.startsWith('tools') || field.startsWith('rule')) return 'tools';
  if (field.startsWith('knowledge')) return 'knowledge';
  if (field.startsWith('subagent')) return 'subagents';
  return 'limits';
}

/**
 * 提交前的校验，规则来自 `agent-spec.schema.json` 与 control-plane 发布时的检查（子智能体的工具集是父智能体的子集、
 * 声明了子智能体就要启用 spawn_subagent、开启前置检索至少绑定一个知识库）。T22 换成 Zod schema。
 */
export function validate(form: AgentDefinitionForm): FormErrors {
  const errors: Record<string, string> = {};
  const name = form.name.trim();
  if (!name) errors['name'] = '写一个名字，项目内不能重复。';
  else if (name.length > 64) errors['name'] = `名字最多 64 个字符，现在是 ${name.length} 个。`;
  if (form.temperature !== null && (form.temperature < 0 || form.temperature > 2))
    errors['model-temperature'] = '温度在 0 到 2 之间。';
  if (form.maxOutputTokens !== null && (form.maxOutputTokens < 1 || form.maxOutputTokens > 32_768))
    errors['model-max-output'] = '最大输出在 1 到 32,768 之间，留空则用模型的默认值。';
  if (!form.systemPrompt.trim()) errors['system-prompt'] = '写上系统提示词：智能体的角色、口径与边界。';
  if (form.subagents.length > 0 && !form.tools.includes('spawn_subagent'))
    errors['tools'] = '声明了子智能体，需要启用"拉起子智能体"（spawn_subagent）。';
  if (form.prefetch && form.knowledgeBaseIds.length === 0)
    errors['knowledge-bases'] = '开启了前置检索，至少绑定一个知识库。';
  if (form.prefetchTopK < 1 || form.prefetchTopK > 50)
    errors['knowledge-top-k'] = '注入的切片数在 1 到 50 之间。';
  // 父智能体的工具：平台工具加挂载的 MCP 工具全名 mcp__{服务名}__{工具}。
  const parentTools = new Set<string>([
    ...form.tools,
    ...form.mcpServers.flatMap((s) => {
      const server = MCP_SERVERS.find((m) => m.id === s.mcpServerId);
      return server ? s.enabledTools.map((t) => `mcp__${server.name}__${t}`) : [];
    }),
  ]);
  form.subagents.forEach((sub, i) => {
    const extra = sub.tools.filter((t) => !parentTools.has(t));
    if (extra.length > 0)
      errors[`subagent-${i}-tools`] =
        `${extra.join('、')} 不在本智能体的工具中，子智能体只能用父智能体启用的工具。`;
    if (sub.timeoutSeconds < 1 || sub.timeoutSeconds > 600)
      errors[`subagent-${i}-timeout`] = '超时在 1 到 600 秒之间。';
  });
  if (form.maxIters < 1 || form.maxIters > 200)
    errors['limits-max-iters'] = '单轮最大推理次数在 1 到 200 之间。';
  return errors;
}
