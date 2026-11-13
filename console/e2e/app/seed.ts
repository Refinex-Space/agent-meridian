import createClient from 'openapi-fetch';

import type { paths as AdminPaths } from '../../src/lib/api/admin';

import { CONTROL_PLANE, FIXTURE, fixtureProject, passwordToken, USERS } from './support';

/** 每次运行的名字不同：用例可以重复执行，不依赖之前留下的数据。 */
export const unique = (prefix: string) =>
  `${prefix}-${Date.now().toString(36)}${Math.floor(Math.random() * 36 ** 2).toString(36)}`;

/** 以平台管理员（租户所有者）调用管理 API，准备用例的数据。 */
export async function adminClient() {
  const token = await passwordToken(USERS.admin.username);
  return createClient<AdminPaths>({ baseUrl: CONTROL_PLANE, headers: { Authorization: `Bearer ${token}` } });
}

export function projectPath(which: 'main' | 'other' = 'main') {
  const tenantId = process.env['E2E_TENANT_ID'];
  if (!tenantId) throw new Error('缺少 E2E_TENANT_ID：全局准备没有执行');
  return { tenant_id: tenantId, project_id: fixtureProject(which) };
}

/** 全局准备登记的模型、技能与知识库的 ID（按名字查）。 */
export async function fixtureIds() {
  const api = await adminClient();
  const path = projectPath();
  const models = (
    await api.GET('/admin/v1/tenants/{tenant_id}/models', {
      params: { path: { tenant_id: path.tenant_id }, query: { limit: 100 } },
    })
  ).data!.data;
  const skills = (
    await api.GET('/admin/v1/tenants/{tenant_id}/projects/{project_id}/skills', {
      params: { path, query: { limit: 100 } },
    })
  ).data!.data;
  const model = (name: string) => models.find((m) => m.name === name)!.id;
  return {
    chat: model(FIXTURE.models[0].name),
    mini: model(FIXTURE.models[1].name),
    skill: skills.find((s) => s.name === FIXTURE.skill)!.id,
  };
}

/** 一份最小的草稿规格。 */
export function baseSpec(modelId: string): Record<string, unknown> {
  return {
    model: { model_id: modelId, fallback_model_id: null, max_output_tokens: null, temperature: null },
    system_prompt: '你是经营分析助手。\n回答简洁，给出数据来源。',
    tools: ['read_file'],
    mcp_servers: [],
    skills: [],
    subagents: [],
    permissions: {
      mode: 'default',
      rules: [
        { tool_name: 'read_file', rule_content: 'reports/**', behavior: 'allow', layer: 'agent_version' },
      ],
    },
    compaction: { reserved_tokens: 20000 },
    plan_mode: false,
    knowledge: { knowledge_base_ids: [], prefetch: false, prefetch_top_k: 5, query_rewrite_model: null },
    max_iters: 50,
    expose_thinking: false,
  };
}

/**
 * 经管理 API 建一个智能体：依次建版本并按 `publish` 发布（版本号从 1 开始）。返回智能体 ID。
 */
export async function seedAgent(
  name: string,
  versions: readonly { spec: Record<string, unknown>; publish: boolean }[],
  which: 'main' | 'other' = 'main',
): Promise<string> {
  const api = await adminClient();
  const path = projectPath(which);
  const agent = await api.POST('/admin/v1/tenants/{tenant_id}/projects/{project_id}/agents', {
    params: { path },
    body: { name, description: '经营分析：销售、毛利与品类的周报与问答' },
  });
  if (!agent.data) throw new Error(`创建智能体失败：${JSON.stringify(agent.error)}`);
  for (const v of versions) {
    const created = await api.POST(
      '/admin/v1/tenants/{tenant_id}/projects/{project_id}/agents/{agent_id}/versions',
      {
        params: { path: { ...path, agent_id: agent.data.id } },
        body: { spec: v.spec },
      },
    );
    if (!created.data) throw new Error(`创建版本失败：${JSON.stringify(created.error)}`);
    if (v.publish) {
      const published = await api.POST(
        '/admin/v1/tenants/{tenant_id}/projects/{project_id}/agents/{agent_id}/versions/{version}:publish',
        { params: { path: { ...path, agent_id: agent.data.id, version: created.data.version } } },
      );
      if (!published.data) throw new Error(`发布失败：${JSON.stringify(published.error)}`);
    }
  }
  return agent.data.id;
}
