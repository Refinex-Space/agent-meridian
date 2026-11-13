import { expect, test, type Page } from '@playwright/test';
import createClient from 'openapi-fetch';

import type { paths as V1Paths } from '../../src/lib/api/v1';

import { adminClient, baseSpec, fixtureIds, projectPath, seedAgent, unique } from './seed';
import { evidencePath, fixtureProject, openAs, RUNTIME } from './support';

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
    const api = await adminClient();
    const path = projectPath();
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

  test('子智能体的工具超出父智能体时在本地拦截，不发出保存请求（T22-A02）', async ({ page }) => {
    const ids = await fixtureIds();
    const spec = {
      ...baseSpec(ids.chat),
      tools: ['read_file', 'web_fetch', 'spawn_subagent'],
      subagents: [
        {
          name: 'reader',
          description: '阅读并总结给定的网页',
          model: { model_id: ids.mini, fallback_model_id: null, max_output_tokens: null, temperature: null },
          system_prompt: '',
          tools: ['read_file', 'web_fetch'],
          skills: [],
          permissions: { mode: 'dont_ask', rules: [] },
          max_iters: 20,
          timeout: 'PT300S',
        },
      ],
    };
    const agentId = await seedAgent(unique('e2e-local'), [{ spec, publish: false }]);
    await openAs(page, 'developer', `/projects/${fixtureProject()}/agents/${agentId}/versions/1/edit`);
    // 父智能体去掉 web_fetch：子智能体仍选着它。
    await page.getByRole('checkbox', { name: 'web_fetch 读取网页' }).uncheck();
    // 温度超出范围：结构校验。
    await page.getByRole('textbox', { name: '温度' }).fill('2.5');
    let saves = 0;
    page.on('request', (r) => {
      if (r.method() === 'PUT' && r.url().includes('/versions/1')) saves += 1;
    });
    await page.getByRole('button', { name: '保存草稿' }).click();
    await expect(page.getByRole('status').filter({ hasText: '有 2 处需要修改' })).toBeVisible();
    await expect(page.getByText('温度在 0 到 2 之间。')).toBeVisible();
    await expect(page.getByText(/web_fetch 不在本智能体的工具中/)).toBeVisible();
    await expect(page.getByRole('textbox', { name: '温度' })).toBeFocused();
    const nav = page.getByRole('navigation', { name: '表单分组' });
    await expect(nav.getByRole('link', { name: /模型.*有错误/ })).toBeVisible();
    await expect(nav.getByRole('link', { name: /子智能体.*有错误/ })).toBeVisible();
    const shot = evidencePath('agent-edit-invalid-light-1280.png');
    if (shot) await page.screenshot({ path: shot });
    expect(saves).toBe(0);
  });

  test('服务端的校验错误定位到字段（T22-A02）', async ({ page }) => {
    const ids = await fixtureIds();
    // 草稿引用一个不存在的模型：结构合法，发布时服务端返回 spec.model.model_id。
    const agentId = await seedAgent(unique('e2e-server'), [
      { spec: baseSpec('mdl_00000000000000000000000000'), publish: false },
    ]);
    expect(ids.chat).toMatch(/^mdl_/);
    await openAs(page, 'developer', `/projects/${fixtureProject()}/agents/${agentId}/versions/1/edit`);
    await expect(page.getByRole('button', { name: '主模型' })).toContainText('已删除或不可用');
    await page.getByRole('button', { name: '发布 v1' }).click();
    await page.getByRole('dialog').getByRole('button', { name: '发布 v1' }).click();
    await expect(page.getByText(/模型不存在：mdl_00000000000000000000000000/)).toBeVisible();
    await expect(page.getByRole('button', { name: '主模型' })).toBeFocused();
    await expect(page.getByRole('status').filter({ hasText: '有 1 处需要修改' })).toBeVisible();
    // 改掉之后错误消失。
    await page.getByRole('button', { name: '主模型' }).click();
    await page.getByRole('option', { name: /scripted-chat/ }).click();
    await expect(page.getByText(/模型不存在/)).toHaveCount(0);
  });

  test('发布前展示与当前版本的差异，版本对比页一致，基于旧版本创建新草稿（T22-A03）', async ({ page }) => {
    const ids = await fixtureIds();
    const name = unique('e2e-diff');
    const agentId = await seedAgent(name, [{ spec: baseSpec(ids.chat), publish: true }]);
    const project = fixtureProject();
    await openAs(page, 'developer', `/projects/${project}/agents/${agentId}`);
    await expect(page.getByText('已发布 v1')).toBeVisible();
    await page.getByRole('button', { name: '编辑定义' }).click();
    await expect(page).toHaveURL(new RegExp(`/agents/${agentId}/versions/2/edit$`));

    await page.getByRole('textbox', { name: '温度' }).fill('0.3');
    await page.getByRole('checkbox', { name: 'web_fetch 读取网页' }).check();
    await page.getByRole('button', { name: '发布 v2' }).click();
    const dialog = page.getByRole('dialog', { name: `发布 ${name} v2` });
    await expect(dialog).toContainText('正在使用 v1 的 0 个会话不受影响');
    const diff = dialog.getByRole('table', { name: 'v1 与 v2 的差异' });
    await expect(diff.getByRole('row', { name: /温度/ })).toContainText('0.3');
    await expect(diff.getByRole('row', { name: /平台工具/ })).toContainText('新增 web_fetch');
    // 发布时写入的快照字段（智能体名、版本 ID）不出现在草稿的差异里。
    await expect(diff.getByRole('row')).toHaveCount(3);
    const shot = evidencePath('agent-publish-diff-light-1280.png');
    if (shot) await page.screenshot({ path: shot });
    await dialog.getByRole('button', { name: '发布 v2' }).click();
    await expect(page.getByText('已发布 v2')).toBeVisible();

    await page.getByRole('tab', { name: '版本' }).click();
    await page.getByRole('link', { name: '与 v1 对比' }).click();
    await expect(page).toHaveURL(/compare\?base=1&target=2$/);
    const compare = page.getByRole('table', { name: 'v1 与 v2 的差异' });
    await expect(compare.getByRole('row', { name: /温度/ })).toContainText('0.3');
    await expect(compare.getByRole('row', { name: /平台工具/ })).toContainText('新增 web_fetch');
    const compareShot = evidencePath('agent-compare-light-1280.png');
    if (compareShot) await page.screenshot({ path: compareShot });

    // 回滚为新草稿：基于 v1 创建 v3，内容与 v1 相同。
    await page.getByRole('button', { name: '创建草稿' }).click();
    await page.getByRole('menuitem', { name: '基于 v1 创建草稿' }).click();
    await expect(page).toHaveURL(new RegExp(`/agents/${agentId}/versions/3/edit$`));
    await expect(page.getByRole('textbox', { name: '温度' })).toHaveValue('');
    await expect(page.getByRole('checkbox', { name: 'web_fetch 读取网页' })).not.toBeChecked();
  });

  test('项目观察者：能看，不能新建与编辑，编辑页只读并说明原因', async ({ page }) => {
    const ids = await fixtureIds();
    const agentId = await seedAgent(unique('e2e-viewer'), [{ spec: baseSpec(ids.chat), publish: false }]);
    const project = fixtureProject();
    await openAs(page, 'viewer', `/projects/${project}/agents`);
    const create = page.getByRole('button', { name: '新建智能体' }).first();
    await expect(create).toHaveAttribute('aria-disabled', 'true');
    await expect(create).toHaveAccessibleDescription(/你在华东经营分析中是项目观察者，不能管理智能体/);
    await page.goto(`/projects/${project}/agents/${agentId}/versions/1/edit`);
    await expect(page.getByRole('status').filter({ hasText: '不能编辑智能体' })).toBeVisible();
    await expect(page.getByRole('button', { name: '保存草稿' })).toHaveCount(0);
    await expect(page.getByRole('textbox', { name: '名称' })).toHaveAttribute('readonly', '');
  });
});
