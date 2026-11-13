import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

import { adminClient, projectPath, unique } from './seed';
import { FIXTURE, fixtureProject, openAs } from './support';

/**
 * 能力、模型与凭据页面的无障碍（WCAG 2.2 AA，含对比度）：真实页面、亮暗两套主题，整页检查，含对话框与抽屉（在 Portal 里）。
 */
const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'];

async function violations(page: Page) {
  await page.waitForTimeout(300);
  const results = await new AxeBuilder({ page }).withTags(TAGS).analyze();
  return results.violations.map((v) => ({
    id: v.id,
    impact: v.impact,
    targets: v.nodes.slice(0, 5).map((n) => n.target.join(' ')),
  }));
}

/** 一个技能、一个价格表：详情页有内容。 */
async function seed() {
  const api = await adminClient();
  const path = projectPath();
  const name = unique('a11y-skill');
  const skill = await api.POST('/admin/v1/tenants/{tenant_id}/projects/{project_id}/skills', {
    params: { path },
    body: {
      files: [
        {
          path: 'SKILL.md',
          content: `---\nname: ${name}\ndescription: 无障碍检查用的技能。\n---\n\n# 说明\n`,
        },
      ],
    },
  });
  const book = await api.POST('/admin/v1/tenants/{tenant_id}/price-books', {
    params: { path: { tenant_id: path.tenant_id } },
    body: {
      name: unique('a11y-price'),
      currency: 'CNY',
      prices: { input: '1', output: '2', cache_read: '0', cache_write: '0', reasoning: '0' },
    },
  });
  const servers = await api.GET('/admin/v1/tenants/{tenant_id}/projects/{project_id}/mcp-servers', {
    params: { path, query: { limit: 100 } },
  });
  return { skillId: skill.data!.id, priceBookId: book.data!.id, mcpId: servers.data!.data[0]?.id ?? null };
}

for (const theme of ['light', 'dark'] as const) {
  test(`能力、模型与凭据的页面没有无障碍违规（${theme === 'light' ? '亮色' : '暗色'}）`, async ({ page }) => {
    test.setTimeout(180_000);
    const ids = await seed();
    const base = `/projects/${fixtureProject()}`;
    await page.emulateMedia({ colorScheme: theme });
    const pages: [string, string | RegExp][] = [
      [`${base}/capabilities`, '上传技能'],
      [`${base}/capabilities/skills/${ids.skillId}`, '上传新版本'],
      [`${base}/capabilities?tab=mcp-servers`, '登记 MCP 服务'],
      [`${base}/capabilities?tab=custom-tools`, '创建会话时声明工具'],
      [`${base}/models`, FIXTURE.providerAccount],
      [`${base}/models?tab=catalog`, 'scripted-chat'],
      [`${base}/models?tab=price-books`, '新建价格表'],
      [`${base}/models/price-books/${ids.priceBookId}`, '发布新版本'],
      [`${base}/settings`, '写入凭据'],
    ];
    if (ids.mcpId) pages.push([`${base}/capabilities/mcp-servers/${ids.mcpId}`, '重新探测']);
    await openAs(page, 'admin', pages[0]![0]);
    for (const [url, ready] of pages) {
      await page.goto(url);
      await expect(page.getByText(ready).first()).toBeVisible({ timeout: 20_000 });
      expect(await violations(page), url).toEqual([]);
    }

    // 对话框与抽屉。
    const dialogs: [string, string, string][] = [
      [`${base}/capabilities?tab=mcp-servers`, '登记 MCP 服务', '登记 MCP 服务'],
      [`${base}/models`, '新建供应商账号', '新建供应商账号'],
      [`${base}/models?tab=catalog`, '添加模型', '添加模型'],
      [`${base}/models?tab=price-books`, '新建价格表', '新建价格表'],
      [`${base}/settings`, '写入凭据', '写入凭据'],
    ];
    for (const [url, button, dialog] of dialogs) {
      await page.goto(url);
      await page.getByRole('button', { name: button }).first().click();
      await expect(page.getByRole('dialog', { name: dialog })).toBeVisible();
      expect(await violations(page), dialog).toEqual([]);
      await page.keyboard.press('Escape');
    }
    await page.goto(`${base}/models?tab=catalog`);
    await page
      .getByRole('row')
      .filter({ hasText: 'scripted-chat' })
      .getByRole('button', { name: '测试' })
      .click();
    const test_ = page.getByRole('dialog', { name: '测试 scripted-chat' });
    await test_.getByRole('button', { name: '发送测试请求' }).click();
    await expect(test_).toContainText('调用成功');
    expect(await violations(page), '模型测试').toEqual([]);
  });
}
