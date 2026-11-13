import { expect, test, type Page } from '@playwright/test';
import createClient from 'openapi-fetch';

import type { paths as AdminPaths } from '../../src/lib/api/admin';
import type { paths as V1Paths } from '../../src/lib/api/v1';

import { CONTROL_PLANE, fixtureProject, openAs, passwordToken, RUNTIME, USERS } from './support';

/** 每次运行的名字不同：用例可以重复执行，不依赖之前留下的数据。 */
const unique = (prefix: string) => `${prefix}-${Date.now().toString(36)}`;

async function admin() {
  const token = await passwordToken(USERS.admin.username);
  return createClient<AdminPaths>({ baseUrl: CONTROL_PLANE, headers: { Authorization: `Bearer ${token}` } });
}

/** 新建智能体，进入草稿 v1 的编辑页。 */
async function createAgent(page: Page, name: string) {
  await page.getByRole('button', { name: '新建智能体' }).first().click();
  const dialog = page.getByRole('dialog', { name: '新建智能体' });
  await dialog.getByRole('textbox', { name: '名称' }).fill(name);
  await dialog.getByRole('textbox', { name: '说明' }).fill('端到端用例建的智能体');
  await dialog.getByRole('button', { name: '主模型' }).click();
  await page.getByRole('option', { name: /scripted-chat/ }).click();
  await dialog.getByRole('button', { name: '新建并编辑' }).click();
  await expect(page).toHaveURL(/\/agents\/agt_[^/]+\/versions\/1\/edit$/);
  await expect(page.getByRole('heading', { name: `编辑 ${name}`, level: 1 })).toBeVisible();
}

/** 在 Monaco 中输入系统提示词。 */
async function typePrompt(page: Page, text: string) {
  const editor = page.locator('#field-spec-system_prompt');
  await editor.locator('.monaco-editor').waitFor();
  await editor.locator('.view-lines').click();
  await page.keyboard.press('ControlOrMeta+A');
  await page.keyboard.type(text);
}

test.describe('智能体', () => {
  test('从零创建、配置并发布，用 API 创建会话并跑通一轮（T22-A01）', async ({ page }) => {
    const project = fixtureProject();
    const name = unique('e2e-assistant');
    await openAs(page, 'developer', `/projects/${project}/agents`);
    await createAgent(page, name);

    await typePrompt(page, '你是经营分析助手，回答简洁。');
    for (const tool of ['read_file', 'knowledge_search', 'spawn_subagent'])
      await page.getByRole('checkbox', { name: new RegExp(`^${tool}`) }).check();

    // 权限规则：read_file 的路径 glob。
    const rules = page.getByRole('group', { name: '新增规则' });
    await rules.getByRole('textbox', { name: '规则内容' }).fill('reports/**');
    await rules.getByRole('button', { name: '添加规则' }).click();
    await expect(page.getByRole('table', { name: '规则' })).toContainText('reports/**');

    await page.getByRole('button', { name: '绑定知识库' }).click();
    await page.getByRole('option', { name: /季度经营月报/ }).click();
    await page.getByRole('button', { name: '添加技能' }).first().click();
    await page.getByRole('option', { name: /weekly-report/ }).click();

    await page.getByRole('button', { name: '添加子智能体' }).click();
    const sub = page.getByRole('group', { name: /^子智能体 / }).last();
    await sub.getByRole('textbox', { name: '名称' }).fill('reader');
    await sub.getByRole('textbox', { name: '用途' }).fill('阅读文件并给出摘要');
    await sub.getByRole('checkbox', { name: 'read_file' }).check();

    await page.getByRole('button', { name: '保存草稿' }).click();
    await expect(page.getByRole('status').filter({ hasText: '已保存于' })).toBeVisible();

    await page.getByRole('button', { name: '发布 v1' }).click();
    const publish = page.getByRole('dialog', { name: `发布 ${name} v1` });
    await expect(publish).toContainText('发布后就能用');
    await publish.getByRole('button', { name: '发布 v1' }).click();
    await expect(page).toHaveURL(/\/agents\/agt_[^/]+$/);
    await expect(page.getByText('已发布 v1')).toBeVisible();
    const agentId = /\/agents\/(agt_[^/?]+)/.exec(page.url())![1]!;

    // 业务系统的调用：项目的 API Key、一个环境，创建会话并发一条消息，脚本化模型回显。
    const api = await admin();
    const path = { tenant_id: process.env['E2E_TENANT_ID']!, project_id: project };
    const env = await api.POST('/admin/v1/tenants/{tenant_id}/projects/{project_id}/environments', {
      params: { path },
      body: { name: unique('e2e-none'), tier: 'none' },
    });
    expect(env.response.status).toBe(201);
    const key = await api.POST('/admin/v1/tenants/{tenant_id}/projects/{project_id}/api-keys', {
      params: { path },
      body: { name: unique('e2e-key'), scopes: ['sessions:read', 'sessions:write'] },
    });
    expect(key.response.status).toBe(201);
    const v1 = createClient<V1Paths>({
      baseUrl: RUNTIME,
      headers: { Authorization: `Bearer ${key.data!.key}` },
    });
    const session = await v1.POST('/v1/sessions', { body: { agent: agentId, environment: env.data!.id } });
    expect(session.response.status).toBe(201);
    const sessionId = session.data!.id;
    expect(session.data!.agent_id).toBe(agentId);
    const sent = await v1.POST('/v1/sessions/{session_id}/events', {
      params: { path: { session_id: sessionId } },
      body: { events: [{ type: 'user.message', content: [{ type: 'text', text: '本周的销售额是多少？' }] }] },
    });
    expect(sent.response.ok).toBe(true);
    await expect
      .poll(
        async () => {
          const events = await v1.GET('/v1/sessions/{session_id}/events', {
            params: { path: { session_id: sessionId }, query: { limit: 100 } },
          });
          return (events.data?.data ?? []).map((e) => e.type);
        },
        { timeout: 30_000 },
      )
      .toEqual(expect.arrayContaining(['agent.message', 'session.status_idle']));
  });
});
