import { expect, test, type Page } from '@playwright/test';
import createClient from 'openapi-fetch';

import type { paths as V1Paths } from '../../src/lib/api/v1';

import { adminClient, baseSpec, fixtureIds, projectPath, seedAgent, unique } from './seed';
import {
  evidencePath,
  FIXTURE,
  fixtureProject,
  KNOWLEDGE,
  openAs,
  passwordToken,
  RUNTIME,
  USERS,
} from './support';

/**
 * 评审截图（T22-A04、A06）：智能体与环境的每个页面在亮暗两种主题、1280 与 1920 两种宽度下各一张，另有加载、空、错误、
 * 无权限状态的亮色 1280。只在设置了 EVIDENCE_DIR 时运行：`EVIDENCE_DIR=../tmp/evidence/T22 pnpm e2e:app evidence-agents`。
 */
test.skip(!process.env['EVIDENCE_DIR'], '未设置 EVIDENCE_DIR');
test.describe.configure({ mode: 'serial' });

interface Showcase {
  agentId: string;
  environmentId: string;
}

let showcase: Showcase;

/** 一个配置齐全的智能体（v1 已发布、v2 草稿）、一个沙箱环境（两个修订）与一个使用它的会话。 */
async function seedShowcase(): Promise<Showcase> {
  const ids = await fixtureIds();
  const api = await adminClient();
  const path = projectPath();
  const token = await passwordToken(USERS.admin.username);
  const knowledge = createClient<V1Paths>({
    baseUrl: KNOWLEDGE,
    headers: { Authorization: `Bearer ${token}`, 'Meridian-Project': fixtureProject() },
  });
  const kb = (
    await knowledge.GET('/v1/knowledge-bases', { params: { query: { limit: 100 } } })
  ).data!.data.find((k) => k.name === FIXTURE.knowledgeBase)!;
  const sub = (name: string, description: string, tools: string[]) => ({
    name,
    description,
    model: { model_id: ids.mini, fallback_model_id: null, max_output_tokens: null, temperature: null },
    system_prompt: `你是${description}`,
    tools,
    skills: [],
    permissions: {
      mode: 'dont_ask',
      rules: [{ tool_name: tools[0], rule_content: null, behavior: 'allow', layer: 'agent_version' }],
    },
    max_iters: 20,
    timeout: 'PT300S',
  });
  const v1 = {
    ...baseSpec(ids.chat),
    system_prompt:
      '你是华东区零售的经营分析助手。\n\n## 口径\n- 销售额按含税计\n- 毛利率 = 毛利 ÷ 销售额\n\n## 输出\n先给结论，再列出数据来源与下滑的品类。',
    tools: [
      'read_file',
      'list_files',
      'grep_files',
      'write_file',
      'knowledge_search',
      'web_fetch',
      'spawn_subagent',
    ],
    skills: [{ skill_id: ids.skill, version: 1 }],
    knowledge: {
      knowledge_base_ids: [kb.id],
      prefetch: true,
      prefetch_top_k: 5,
      query_rewrite_model: ids.mini,
    },
    permissions: {
      mode: 'default',
      rules: [
        { tool_name: 'read_file', rule_content: 'reports/**', behavior: 'allow', layer: 'agent_version' },
        { tool_name: 'write_file', rule_content: 'outputs/**', behavior: 'allow', layer: 'agent_version' },
        {
          tool_name: 'web_fetch',
          rule_content: 'domain:bi.example.com',
          behavior: 'ask',
          layer: 'agent_version',
        },
        { tool_name: 'knowledge_search', rule_content: null, behavior: 'allow', layer: 'agent_version' },
      ],
    },
    subagents: [
      sub('data-reader', '读取报表并汇总的数据助手', ['read_file', 'list_files', 'grep_files']),
      sub('policy-reader', '解读促销政策的知识助手', ['knowledge_search']),
      sub('web-researcher', '查阅内部 BI 页面的研究助手', ['web_fetch', 'read_file']),
    ],
  };
  const v2 = {
    ...v1,
    model: { model_id: ids.chat, fallback_model_id: null, max_output_tokens: null, temperature: 0.2 },
    tools: [...v1.tools, 'bash'],
  };
  const agentId = await seedAgent(unique('sales-analyst'), [
    { spec: v1, publish: true },
    { spec: v2, publish: false },
  ]);

  const env = await api.POST('/admin/v1/tenants/{tenant_id}/projects/{project_id}/environments', {
    params: { path },
    body: {
      name: unique('生产只读'),
      tier: 'sandbox',
      size: 'medium',
      egress_allowlist: ['erp-gateway.example.com', 'bi.example.com'],
    },
  });
  await api.PUT('/admin/v1/tenants/{tenant_id}/projects/{project_id}/environments/{environment_id}', {
    params: { path: { ...path, environment_id: env.data!.id } },
    body: {
      name: env.data!.name,
      tier: 'sandbox',
      size: 'medium',
      egress_allowlist: [
        'erp-gateway.example.com',
        'bi.example.com',
        '*.oss-cn-hangzhou.aliyuncs.com',
        'pypi.org',
      ],
    },
  });
  // 一个使用中的会话：会话标签页有内容。
  const none = await api.POST('/admin/v1/tenants/{tenant_id}/projects/{project_id}/environments', {
    params: { path },
    body: { name: unique('对话'), tier: 'none' },
  });
  const key = await api.POST('/admin/v1/tenants/{tenant_id}/projects/{project_id}/api-keys', {
    params: { path },
    body: { name: unique('evidence'), scopes: ['sessions:read', 'sessions:write'] },
  });
  const v1Api = createClient<V1Paths>({
    baseUrl: RUNTIME,
    headers: { Authorization: `Bearer ${key.data!.key}` },
  });
  await v1Api.POST('/v1/sessions', {
    body: { agent: agentId, environment: none.data!.id, end_user_id: 'u_8231' },
  });
  return { agentId, environmentId: env.data!.id };
}

const THEMES = ['light', 'dark'] as const;
const WIDTHS = [1280, 1920] as const;

async function shoot(page: Page, name: string) {
  const file = evidencePath(`${name}.png`);
  if (!file) return;
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(400);
  await page.screenshot({ path: file });
}

/** 打开页面并等它就绪；`ready` 是页面上一定会出现的文字。 */
async function open(
  page: Page,
  theme: (typeof THEMES)[number],
  width: number,
  url: string,
  ready: string | RegExp,
) {
  await page.emulateMedia({ colorScheme: theme });
  await page.setViewportSize({ width, height: width === 1280 ? 800 : 1080 });
  await openAs(page, 'developer', url);
  await expect(page.getByText(ready).first()).toBeVisible({ timeout: 20_000 });
}

test.beforeAll(async () => {
  showcase = await seedShowcase();
});

for (const theme of THEMES) {
  for (const width of WIDTHS) {
    const tag = `${theme}-${width}`;
    test(`智能体与环境的页面 ${tag}`, async ({ page }) => {
      const project = fixtureProject();
      const agents = `/projects/${project}/agents`;
      const agent = `${agents}/${showcase.agentId}`;

      await open(page, theme, width, agents, 'sales-analyst');
      await shoot(page, `agents-list-${tag}`);

      await page.goto(agent);
      await expect(page.getByText('当前版本 v1')).toBeVisible();
      await shoot(page, `agent-detail-${tag}`);
      await page.getByRole('tab', { name: '版本' }).click();
      await shoot(page, `agent-versions-${tag}`);
      await page.getByRole('tab', { name: '会话' }).click();
      await expect(page.getByText('u_8231')).toBeVisible();
      await shoot(page, `agent-sessions-${tag}`);
      await page.getByRole('tab', { name: '子智能体' }).click();
      await expect(page.locator('.react-flow__node')).toHaveCount(4);
      await shoot(page, `agent-topology-${tag}`);

      await page.goto(`${agent}/versions/2/edit`);
      await expect(page.locator('#field-spec-system_prompt .monaco-editor')).toBeVisible();
      await shoot(page, `agent-edit-${tag}`);
      await page.getByRole('navigation', { name: '表单分组' }).getByRole('link', { name: '提示词' }).click();
      await shoot(page, `agent-edit-prompt-${tag}`);
      await page.getByRole('navigation', { name: '表单分组' }).getByRole('link', { name: '权限' }).click();
      await shoot(page, `agent-edit-permissions-${tag}`);
      await page
        .getByRole('navigation', { name: '表单分组' })
        .getByRole('link', { name: '子智能体' })
        .click();
      await shoot(page, `agent-edit-subagents-${tag}`);
      await page.getByRole('button', { name: '发布 v2' }).click();
      await expect(page.getByRole('table', { name: 'v1 与 v2 的差异' })).toBeVisible();
      await shoot(page, `agent-publish-${tag}`);
      await page.getByRole('button', { name: '取消' }).click();

      await page.goto(`${agent}/compare?base=1&target=2`);
      await expect(page.getByRole('table', { name: 'v1 与 v2 的差异' })).toBeVisible();
      await shoot(page, `agent-compare-${tag}`);

      await page.goto(`/projects/${project}/environments`);
      await expect(page.getByText('生产只读').first()).toBeVisible();
      await shoot(page, `environments-list-${tag}`);
      await page.goto(`/projects/${project}/environments/${showcase.environmentId}`);
      await expect(page.getByRole('table', { name: '修订历史' })).toBeVisible();
      await shoot(page, `environment-edit-${tag}`);
      await page
        .getByRole('navigation', { name: '表单分组' })
        .getByRole('link', { name: '修订历史' })
        .click();
      await shoot(page, `environment-revisions-${tag}`);
    });
  }
}

test('状态：加载、空、错误、无权限（亮色 1280）', async ({ page }) => {
  const project = fixtureProject();
  const other = fixtureProject('other');
  await page.emulateMedia({ colorScheme: 'light' });
  await page.setViewportSize({ width: 1280, height: 800 });
  await openAs(page, 'developer', `/projects/${other}/agents`);

  // 空态：另一个项目还没有智能体与环境。
  await expect(page.getByText('还没有智能体')).toBeVisible();
  await shoot(page, 'state-agents-empty');
  await page.goto(`/projects/${other}/environments`);
  await expect(page.getByText('还没有环境')).toBeVisible();
  await shoot(page, 'state-environments-empty');

  // 筛选为空。
  await page.goto(`/projects/${project}/agents?q=no-such-agent`);
  await expect(page.getByText('没有名称或说明匹配的智能体')).toBeVisible();
  await shoot(page, 'state-agents-filtered');

  // 加载：请求被挂起时的骨架（150ms 之后出现）。
  await page.route('**/agents?*', () => undefined);
  await page.goto(`/projects/${project}/agents`);
  await page.waitForTimeout(600);
  await shoot(page, 'state-agents-loading');
  await page.unroute('**/agents?*');
  await page.route(`**/agents/${showcase.agentId}`, () => undefined);
  await page.goto(`/projects/${project}/agents/${showcase.agentId}/versions/2/edit`);
  await page.waitForTimeout(600);
  await shoot(page, 'state-agent-edit-loading');
  await page.unroute(`**/agents/${showcase.agentId}`);

  // 错误：服务端返回 problem+json。
  const problem = (status: number, code: string, detail: string) => ({
    status,
    contentType: 'application/problem+json',
    body: JSON.stringify({
      type: `urn:meridian:error:${code}`,
      title: '错误',
      status,
      detail,
      instance: '/admin/v1/…',
      code,
      request_id: 'req_01JCQC2E5G8J1L4N7Q0S3V6X9Z',
    }),
  });
  await page.route('**/environments?*', (route) =>
    route.fulfill(problem(503, 'service.unavailable', 'control-plane 暂时不可用。')),
  );
  await page.goto(`/projects/${project}/environments`);
  await expect(page.getByText('无法加载环境')).toBeVisible({ timeout: 20_000 });
  await shoot(page, 'state-environments-error');
  await page.unroute('**/environments?*');
  await page.route('**/versions/99', (route) =>
    route.fulfill(problem(404, 'agent.version_not_found', '版本 99 不存在，可能已被其他成员删除。')),
  );
  await page.goto(`/projects/${project}/agents/${showcase.agentId}/versions/99/edit`);
  await expect(page.getByText('无法打开这个草稿')).toBeVisible({ timeout: 20_000 });
  await shoot(page, 'state-agent-edit-error');
  await page.unroute('**/versions/99');

  // 无权限：当前用户在项目上没有查看智能体的权限（审批人一类的角色）。
  await page.route('**/admin/v1/me/access?*', async (route) => {
    const response = await route.fetch();
    const access = (await response.json()) as { permissions: string[] };
    await route.fulfill({
      response,
      json: { ...access, permissions: access.permissions.filter((p) => !p.startsWith('agent_')) },
    });
  });
  await page.goto(`/projects/${project}/agents`);
  await expect(page.getByText('没有权限查看智能体')).toBeVisible();
  await shoot(page, 'state-agents-no-permission');
  await page.goto(`/projects/${project}/environments/${showcase.environmentId}`);
  await expect(page.getByText('没有权限查看环境')).toBeVisible();
  await shoot(page, 'state-environment-no-permission');
});

test('只读与校验错误（亮色 1280）', async ({ page }) => {
  const project = fixtureProject();
  await page.emulateMedia({ colorScheme: 'light' });
  await page.setViewportSize({ width: 1280, height: 800 });
  await openAs(page, 'viewer', `/projects/${project}/agents/${showcase.agentId}/versions/2/edit`);
  await expect(page.getByRole('status').filter({ hasText: '不能编辑智能体' })).toBeVisible();
  await shoot(page, 'state-agent-edit-read-only');
  await page.goto(`/projects/${project}/agents`);
  await expect(page.getByText('sales-analyst').first()).toBeVisible();
  await shoot(page, 'state-agents-viewer');

  await page.goto(`/projects/${project}/environments/${showcase.environmentId}`);
  await expect(page.getByRole('table', { name: '修订历史' })).toBeVisible();
  await shoot(page, 'state-environment-read-only');
});

test('环境的白名单校验错误（亮色 1280）', async ({ page }) => {
  const project = fixtureProject();
  await page.emulateMedia({ colorScheme: 'light' });
  await page.setViewportSize({ width: 1280, height: 800 });
  await openAs(page, 'developer', `/projects/${project}/environments/${showcase.environmentId}`);
  await page.getByRole('navigation', { name: '表单分组' }).getByRole('link', { name: '出网' }).click();
  await page.getByRole('textbox', { name: '允许访问的域名' }).fill('https://pypi.org/simple');
  await page.getByRole('button', { name: '添加', exact: true }).click();
  await expect(page.getByText('不带协议与路径')).toBeVisible();
  await shoot(page, 'state-environment-invalid-domain');
  await page.getByRole('textbox', { name: '允许访问的域名' }).fill('');
  await page.getByRole('textbox', { name: '名称' }).fill('');
  await page.getByRole('button', { name: /保存/ }).click();
  await expect(page.getByText('写一个名字，项目内不能重复。')).toBeVisible();
  await shoot(page, 'state-environment-invalid');
});
