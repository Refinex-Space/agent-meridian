import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

import { expect, test, type Page } from '@playwright/test';

import { adminClient, baseSpec, fixtureIds, projectPath, seedAgent, unique } from './seed';
import { evidencePath, FIXTURE, fixtureProject, openAs } from './support';

/**
 * 评审截图（T23-A06）：能力、模型与凭据的每个页面与主要对话框在亮暗两种主题、1280 与 1920 两种宽度下各一张，另有加载、空、
 * 错误、无权限、长内容等状态的亮色 1280。只在设置了 EVIDENCE_DIR 时运行：
 * `EVIDENCE_DIR=../tmp/evidence/T23 pnpm e2e:app evidence-capabilities`。
 */
test.skip(!process.env['EVIDENCE_DIR'], '未设置 EVIDENCE_DIR');
test.describe.configure({ mode: 'serial' });

interface Showcase {
  skillId: string;
  skillName: string;
  mcpId: string;
  priceBookId: string;
  priceBookName: string;
  modelName: string;
}

let showcase: Showcase;

const SKILL_MD = (name: string) => `---
name: ${name}
description: 按华东区零售的经营分析口径整理周报：汇总销售额与毛利，列出下滑的品类与门店，给出下周的跟进建议。
license: Apache-2.0
allowed-tools:
  - read_file
  - bash
---

# 经营周报

1. 读取 reports/ 下本周的销售与毛利明细。
2. 运行 scripts/summarize.py 汇总到品类与门店。
3. 列出毛利率下滑超过 2 个百分点的品类，写明原因。
4. 输出 outputs/weekly-report.md。
`;

function skillFolder(name: string): string {
  const root = path.join(mkdtempSync(path.join(tmpdir(), 'meridian-skill-')), name);
  mkdirSync(path.join(root, 'scripts'), { recursive: true });
  mkdirSync(path.join(root, 'references'), { recursive: true });
  writeFileSync(path.join(root, 'SKILL.md'), SKILL_MD(name));
  writeFileSync(path.join(root, 'scripts', 'summarize.py'), 'import csv\nimport sys\n\nprint(sys.argv)\n');
  writeFileSync(
    path.join(root, 'references', 'metrics.md'),
    '# 口径\n\n- 销售额按含税计\n- 毛利率 = 毛利 ÷ 销售额\n',
  );
  writeFileSync(path.join(root, '.DS_Store'), Buffer.from([0, 1, 2, 3]));
  return root;
}

/** 技能（两个版本，被一个已发布的智能体引用）、MCP 服务（工具已标注）、价格表（两个版本）与用它计价的模型、两个凭据。 */
async function seedShowcase(): Promise<Showcase> {
  const api = await adminClient();
  const path_ = projectPath();
  const tenant = { tenant_id: path_.tenant_id };
  const ids = await fixtureIds();

  const skillName = unique('weekly-report');
  const files = (desc: string) => [
    { path: 'SKILL.md', content: SKILL_MD(skillName).replace(/^description: .*$/m, `description: ${desc}`) },
    { path: 'scripts/summarize.py', content: 'import csv\nimport sys\n\nprint(sys.argv)\n' },
    { path: 'references/metrics.md', content: '# 口径\n\n- 销售额按含税计\n- 毛利率 = 毛利 ÷ 销售额\n' },
  ];
  const skill = await api.POST('/admin/v1/tenants/{tenant_id}/projects/{project_id}/skills', {
    params: { path: path_ },
    body: { files: files('按华东区零售的经营分析口径整理周报。') },
  });
  const skillId = skill.data!.id;
  await api.POST('/admin/v1/tenants/{tenant_id}/projects/{project_id}/skills/{skill_id}/versions', {
    params: { path: { ...path_, skill_id: skillId } },
    body: { files: files('按华东区零售的经营分析口径整理周报：汇总销售额与毛利，列出下滑的品类与门店。') },
  });
  await seedAgent(unique('sales-analyst'), [
    { spec: { ...baseSpec(ids.chat), skills: [{ skill_id: skillId, version: 2 }] }, publish: true },
  ]);

  // MCP：knowledge 自带的服务，令牌是只读知识库的 API Key，凭据只给本项目。
  const key = await api.POST('/admin/v1/tenants/{tenant_id}/projects/{project_id}/api-keys', {
    params: { path: path_ },
    body: { name: unique('知识检索'), scopes: ['knowledge:read'] },
  });
  const mcpSecret = await api.POST('/admin/v1/tenants/{tenant_id}/secrets', {
    params: { path: tenant },
    body: { name: unique('知识检索令牌'), project_id: path_.project_id, value: `Bearer ${key.data!.key}` },
  });
  const mcp = await api.POST('/admin/v1/tenants/{tenant_id}/projects/{project_id}/mcp-servers', {
    params: { path: path_ },
    body: {
      name: unique('kb').replaceAll('-', '_'),
      transport: 'streamable_http',
      url: 'http://knowledge:18083/mcp/knowledge',
      header_secret_ids: { Authorization: mcpSecret.data!.id },
      side_effect: 'idempotent',
      tool_side_effects: { knowledge_search: 'none' },
    },
  });

  // 一个凭据无效的供应商账号：连通性测试得到"凭据无效"（只列模型清单，不产生费用）。
  const fakeKey = await api.POST('/admin/v1/tenants/{tenant_id}/secrets', {
    params: { path: tenant },
    body: { name: unique('DashScope 测试'), project_id: null, value: 'sk-invalid-for-screenshots' },
  });
  const account = await api.POST('/admin/v1/tenants/{tenant_id}/provider-accounts', {
    params: { path: tenant },
    body: {
      name: unique('DashScope 测试'),
      provider: 'openai_compatible',
      base_url: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
      secret_id: fakeKey.data!.id,
      rate_limit: { rpm: 60, tpm: 200000 },
    },
  });
  await api.POST('/admin/v1/tenants/{tenant_id}/provider-accounts/{provider_account_id}:test', {
    params: { path: { ...tenant, provider_account_id: account.data!.id } },
  });

  const priceBookName = unique('通义千问 Plus');
  const book = await api.POST('/admin/v1/tenants/{tenant_id}/price-books', {
    params: { path: tenant },
    body: {
      name: priceBookName,
      currency: 'CNY',
      prices: { input: '0.8', output: '2', cache_read: '0.16', cache_write: '0.8', reasoning: '8' },
    },
  });
  await api.POST('/admin/v1/tenants/{tenant_id}/price-books/{price_book_id}/versions', {
    params: { path: { ...tenant, price_book_id: book.data!.id } },
    body: {
      prices: { input: '0.8', output: '2.4', cache_read: '0.16', cache_write: '0.8', reasoning: '9.6' },
    },
  });
  const accounts = await api.GET('/admin/v1/tenants/{tenant_id}/provider-accounts', {
    params: { path: tenant, query: { limit: 100 } },
  });
  const scripted = accounts.data!.data.find((a) => a.name === FIXTURE.providerAccount)!;
  const modelName = unique('qwen-plus-sim');
  await api.POST('/admin/v1/tenants/{tenant_id}/models', {
    params: { path: tenant },
    body: {
      provider_account_id: scripted.id,
      name: modelName,
      kind: 'chat',
      upstream_model: 'scripted',
      context_window: 131072,
      max_output_tokens: 8192,
      capabilities: { tool_calls: true, vision: false, reasoning: true },
      price_book_id: book.data!.id,
      fallback_model_id: ids.mini,
    },
  });
  return {
    skillId,
    skillName,
    mcpId: mcp.data!.id,
    priceBookId: book.data!.id,
    priceBookName,
    modelName,
  };
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

async function open(
  page: Page,
  theme: (typeof THEMES)[number],
  width: number,
  url: string,
  ready: string | RegExp,
) {
  await page.emulateMedia({ colorScheme: theme });
  await page.setViewportSize({ width, height: width === 1280 ? 800 : 1080 });
  await openAs(page, 'admin', url);
  await expect(page.getByText(ready).first()).toBeVisible({ timeout: 20_000 });
}

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

test.beforeAll(async () => {
  showcase = await seedShowcase();
});

for (const theme of THEMES) {
  for (const width of WIDTHS) {
    const tag = `${theme}-${width}`;
    test(`能力、模型与凭据的页面 ${tag}`, async ({ page }) => {
      const project = fixtureProject();
      const base = `/projects/${project}`;

      await open(page, theme, width, `${base}/capabilities`, showcase.skillName);
      await shoot(page, `capabilities-skills-${tag}`);
      await page.getByRole('button', { name: '上传技能' }).first().click();
      await page.getByLabel('选择技能文件夹').setInputFiles(skillFolder(unique('store-review')));
      await expect(page.getByRole('dialog').getByText('已忽略 1 个隐藏文件')).toBeVisible();
      await shoot(page, `skill-upload-preview-${tag}`);
      await page.getByRole('button', { name: '取消' }).click();

      await page.goto(`${base}/capabilities/skills/${showcase.skillId}`);
      await expect(page.getByRole('heading', { name: 'v2 的文件' })).toBeVisible();
      await shoot(page, `skill-detail-${tag}`);

      await page.goto(`${base}/capabilities?tab=mcp-servers`);
      await expect(page.getByRole('table', { name: 'MCP 服务列表' })).toBeVisible();
      await shoot(page, `capabilities-mcp-${tag}`);
      await page.getByRole('button', { name: '登记 MCP 服务' }).first().click();
      await expect(page.getByRole('dialog', { name: '登记 MCP 服务' })).toBeVisible();
      await shoot(page, `mcp-register-${tag}`);
      await page.getByRole('button', { name: '取消' }).click();
      await page.goto(`${base}/capabilities/mcp-servers/${showcase.mcpId}`);
      await expect(page.getByRole('table', { name: '工具清单' })).toContainText('knowledge_search');
      await shoot(page, `mcp-detail-${tag}`);

      await page.goto(`${base}/capabilities?tab=custom-tools`);
      await expect(page.getByRole('heading', { name: '创建会话时声明工具' })).toBeVisible();
      await shoot(page, `capabilities-custom-tools-${tag}`);

      await page.goto(`${base}/models`);
      await expect(page.getByRole('table', { name: '供应商账号' })).toContainText('凭据无效');
      await shoot(page, `models-providers-${tag}`);
      await page.getByRole('button', { name: '新建供应商账号' }).click();
      await expect(page.getByRole('dialog', { name: '新建供应商账号' })).toBeVisible();
      await shoot(page, `provider-create-${tag}`);
      await page.getByRole('button', { name: '取消' }).click();

      await page.goto(`${base}/models?tab=catalog`);
      await expect(page.getByRole('table', { name: '模型目录' })).toContainText(showcase.modelName);
      await shoot(page, `models-catalog-${tag}`);
      await page
        .getByRole('row')
        .filter({ hasText: showcase.modelName })
        .getByRole('button', { name: '测试' })
        .click();
      const testDialog = page.getByRole('dialog', { name: `测试 ${showcase.modelName}` });
      await testDialog.getByRole('button', { name: '发送测试请求' }).click();
      await expect(testDialog).toContainText('调用成功');
      await shoot(page, `model-test-${tag}`);
      await testDialog.getByRole('button', { name: '完成' }).click();
      await page.getByRole('button', { name: `${showcase.modelName} 的更多操作` }).click();
      await page.getByRole('menuitem', { name: '修改' }).click();
      await expect(page.getByRole('dialog', { name: `修改 ${showcase.modelName}` })).toBeVisible();
      await shoot(page, `model-edit-${tag}`);
      await page.keyboard.press('Escape');

      await page.goto(`${base}/models?tab=price-books`);
      await expect(page.getByRole('table', { name: '价格表' })).toContainText(showcase.priceBookName);
      await shoot(page, `models-price-books-${tag}`);
      await page.goto(`${base}/models/price-books/${showcase.priceBookId}`);
      await expect(page.getByText('当前 v2')).toBeVisible();
      await shoot(page, `price-book-detail-${tag}`);
      await page.getByRole('button', { name: '发布新版本' }).click();
      await expect(page.getByRole('dialog', { name: /发布 .* v3/ })).toBeVisible();
      await shoot(page, `price-book-publish-${tag}`);
      await page.getByRole('button', { name: '取消' }).click();

      await page.goto(`${base}/settings`);
      await expect(page.getByRole('table', { name: '凭据列表' })).toBeVisible();
      await shoot(page, `settings-secrets-${tag}`);
      await page.getByRole('button', { name: '写入凭据' }).first().click();
      await expect(page.getByRole('dialog', { name: '写入凭据' })).toBeVisible();
      await shoot(page, `secret-create-${tag}`);
    });
  }
}

test('状态：加载、空、错误、无权限、引用与校验（亮色 1280）', async ({ page }) => {
  const project = fixtureProject();
  const other = fixtureProject('other');
  await page.emulateMedia({ colorScheme: 'light' });
  await page.setViewportSize({ width: 1280, height: 800 });
  await openAs(page, 'developer', `/projects/${other}/capabilities`);

  // 空态：另一个项目还没有技能与 MCP 服务。
  await expect(page.getByText('还没有技能')).toBeVisible();
  await shoot(page, 'state-skills-empty');
  await page.goto(`/projects/${other}/capabilities?tab=mcp-servers`);
  await expect(page.getByText('还没有 MCP 服务')).toBeVisible();
  await shoot(page, 'state-mcp-empty');
  await page.goto(`/projects/${project}/models?tab=catalog&q=no-such-model`);
  await expect(page.getByText('没有匹配的模型')).toBeVisible();
  await shoot(page, 'state-catalog-filtered');

  // 项目开发者没有凭据权限：设置里说明原因；模型可以看、可以测试，不能改。
  await page.goto(`/projects/${project}/settings`);
  await expect(page.getByText('没有权限查看凭据')).toBeVisible();
  await shoot(page, 'state-secrets-no-permission');
  await page.goto(`/projects/${project}/models?tab=catalog`);
  await expect(page.getByRole('table', { name: '模型目录' })).toBeVisible();
  await shoot(page, 'state-catalog-developer');

  // 加载：请求挂起时的骨架。
  await page.route('**/admin/v1/**/provider-accounts?*', () => undefined);
  await page.goto(`/projects/${project}/models`);
  await expect(page.getByRole('heading', { name: '模型', level: 1 })).toBeVisible();
  await page.waitForTimeout(600);
  await shoot(page, 'state-providers-loading');
  await page.unroute('**/admin/v1/**/provider-accounts?*');
  await page.route(`**/admin/v1/**/skills/${showcase.skillId}`, () => undefined);
  await page.goto(`/projects/${project}/capabilities/skills/${showcase.skillId}`);
  await expect(page.getByRole('navigation', { name: '主导航' })).toBeVisible();
  await page.waitForTimeout(800);
  await shoot(page, 'state-skill-detail-loading');
  await page.unroute(`**/admin/v1/**/skills/${showcase.skillId}`);

  // 错误：列表与详情加载失败。
  await page.route('**/admin/v1/tenants/*/models?*', (route) =>
    route.fulfill(problem(503, 'service.unavailable', 'control-plane 暂时不可用。')),
  );
  await page.goto(`/projects/${project}/models?tab=catalog`);
  await expect(page.getByText('无法加载模型目录')).toBeVisible({ timeout: 20_000 });
  await shoot(page, 'state-catalog-error');
  await page.unroute('**/admin/v1/tenants/*/models?*');

  // 模型测试失败：预算用完（直通接口的 problem+json 就地显示）。
  await page.route('**/v1/chat/completions', (route) =>
    route.fulfill(problem(429, 'budget.exceeded', '项目本月的 token 预算已用完，调高预算或等下个周期。')),
  );
  await page.goto(`/projects/${project}/models?tab=catalog`);
  await page
    .getByRole('row')
    .filter({ hasText: showcase.modelName })
    .getByRole('button', { name: '测试' })
    .click();
  await page.getByRole('button', { name: '发送测试请求' }).click();
  await expect(page.getByText('调用失败')).toBeVisible();
  await shoot(page, 'state-model-test-error');
  await page.unroute('**/v1/chat/completions');
  await page.keyboard.press('Escape');

  // MCP 连不上：登记失败的原因写在表单里；已登记服务的连接状态为"连不上"。
  await page.route('**/admin/v1/**/mcp-servers', (route) =>
    route.request().method() === 'POST'
      ? route.fulfill(
          problem(
            400,
            'mcp_server.probe_failed',
            '连接 https://crm.example.com/mcp 超时（10 秒）：检查地址与网络策略。',
          ),
        )
      : route.fallback(),
  );
  await page.goto(`/projects/${project}/capabilities?tab=mcp-servers`);
  await page.getByRole('button', { name: '登记 MCP 服务' }).first().click();
  const register = page.getByRole('dialog', { name: '登记 MCP 服务' });
  await register.getByRole('textbox', { name: '服务名' }).fill('crm');
  await register.getByRole('textbox', { name: '地址' }).fill('https://crm.example.com/mcp');
  await register.getByRole('button', { name: '连接并登记' }).click();
  await expect(register.getByText('连不上这个服务，没有保存')).toBeVisible();
  await shoot(page, 'state-mcp-register-failed');
  await page.unroute('**/admin/v1/**/mcp-servers');
  await page.keyboard.press('Escape');
  await page.route(`**/admin/v1/**/mcp-servers/${showcase.mcpId}`, async (route) => {
    const response = await route.fetch();
    const server = (await response.json()) as Record<string, unknown>;
    await route.fulfill({
      response,
      json: {
        ...server,
        last_check: {
          status: 'unreachable',
          http_status: null,
          latency_ms: 10000,
          message: '连接超时（10 秒）：服务没有响应。',
          checked_at: new Date().toISOString(),
        },
      },
    });
  });
  await page.goto(`/projects/${project}/capabilities/mcp-servers/${showcase.mcpId}`);
  await expect(page.getByText('连接超时（10 秒）')).toBeVisible();
  await shoot(page, 'state-mcp-unreachable');
  await page.unroute(`**/admin/v1/**/mcp-servers/${showcase.mcpId}`);

  // 技能上传：包不合法时指出原因，不能上传。
  await page.goto(`/projects/${project}/capabilities`);
  await page.getByRole('button', { name: '上传技能' }).first().click();
  const bad = path.join(mkdtempSync(path.join(tmpdir(), 'meridian-skill-')), 'bad');
  mkdirSync(bad);
  writeFileSync(path.join(bad, 'SKILL.md'), '---\nname: Weekly Report\n---\n\n# 周报\n');
  writeFileSync(path.join(bad, 'logo.png'), Buffer.from([0x89, 0x50, 0x4e, 0x47, 0xff, 0xfe]));
  await page.getByLabel('选择技能文件夹').setInputFiles(bad);
  await expect(page.getByRole('dialog').getByRole('alert')).toBeVisible();
  await shoot(page, 'state-skill-upload-invalid');
  await page.keyboard.press('Escape');

  // 删除被引用的技能：直接列出引用方，不让用户先输入名称再被拒绝。
  await page.goto(`/projects/${project}/capabilities/skills/${showcase.skillId}`);
  await page.getByRole('button', { name: '更多操作' }).click();
  await page.getByRole('menuitem', { name: '删除技能' }).click();
  await expect(page.getByRole('dialog', { name: /仍被引用，不能删除/ })).toBeVisible();
  await shoot(page, 'state-skill-in-use');
});

test('凭据的引用方与供应商账号的校验（亮色 1280）', async ({ page }) => {
  const project = fixtureProject();
  await page.emulateMedia({ colorScheme: 'light' });
  await page.setViewportSize({ width: 1280, height: 800 });
  await openAs(page, 'admin', `/projects/${project}/settings`);
  const row = page.getByRole('row').filter({ hasText: '知识检索令牌' }).first();
  await row.getByRole('button', { name: /的更多操作/ }).click();
  await page.getByRole('menuitem', { name: '删除' }).click();
  await expect(page.getByRole('dialog', { name: /仍被引用，不能删除/ })).toBeVisible();
  await shoot(page, 'state-secret-in-use');
  await page.keyboard.press('Escape');

  await page.goto(`/projects/${project}/models`);
  await page.getByRole('button', { name: '新建供应商账号' }).click();
  await page.getByRole('button', { name: '创建账号' }).click();
  await expect(page.getByText('OpenAI 兼容的账号要写端点。')).toBeVisible();
  await shoot(page, 'state-provider-invalid');
  await page.keyboard.press('Escape');
});

test('项目观察者只读（亮色 1280）', async ({ page }) => {
  const project = fixtureProject();
  await page.emulateMedia({ colorScheme: 'light' });
  await page.setViewportSize({ width: 1280, height: 800 });
  await openAs(page, 'viewer', `/projects/${project}/models`);
  await expect(page.getByRole('table', { name: '供应商账号' })).toBeVisible();
  await shoot(page, 'state-providers-viewer');
  await page.goto(`/projects/${project}/models/price-books/${showcase.priceBookId}`);
  await expect(page.getByText('当前 v2')).toBeVisible();
  await shoot(page, 'state-price-book-viewer');
  await page.goto(`/projects/${project}/capabilities/mcp-servers/${showcase.mcpId}`);
  await expect(page.getByRole('table', { name: '工具清单' })).toBeVisible();
  await shoot(page, 'state-mcp-viewer');
});
