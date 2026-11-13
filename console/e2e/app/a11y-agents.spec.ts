import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

import { baseSpec, fixtureIds, seedAgent, unique } from './seed';
import { fixtureProject, openAs } from './support';

/**
 * 智能体与环境页面的无障碍（WCAG 2.2 AA，含对比度）：真实页面、亮暗两套主题，整页检查（对话框、菜单在 Portal 里）。
 * Monaco 编辑器自己的内部结构（隐藏的输入区、行号）不在检查范围：它有自己的读屏模式，排除 `.monaco-editor`。
 */
const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'];

async function violations(page: Page) {
  await page.waitForTimeout(300);
  const results = await new AxeBuilder({ page }).withTags(TAGS).exclude('.monaco-editor').analyze();
  return results.violations.map((v) => ({
    id: v.id,
    impact: v.impact,
    targets: v.nodes.slice(0, 5).map((n) => n.target.join(' ')),
  }));
}

for (const theme of ['light', 'dark'] as const) {
  test(`智能体与环境的页面没有无障碍违规（${theme === 'light' ? '亮色' : '暗色'}）`, async ({ page }) => {
    test.setTimeout(120_000);
    const ids = await fixtureIds();
    const spec = {
      ...baseSpec(ids.chat),
      tools: ['read_file', 'spawn_subagent'],
      subagents: [
        {
          name: 'reader',
          description: '读取报表',
          model: { model_id: ids.mini, fallback_model_id: null, max_output_tokens: null, temperature: null },
          system_prompt: '',
          tools: ['read_file'],
          skills: [],
          permissions: { mode: 'dont_ask', rules: [] },
          max_iters: 20,
          timeout: 'PT300S',
        },
      ],
    };
    const agentId = await seedAgent(unique('e2e-a11y'), [
      { spec, publish: true },
      { spec: { ...spec, max_iters: 40 }, publish: false },
    ]);
    const project = fixtureProject();
    await page.emulateMedia({ colorScheme: theme });
    const agent = `/projects/${project}/agents/${agentId}`;
    const pages: [string, string | RegExp][] = [
      [`/projects/${project}/agents`, '新建智能体'],
      [agent, '当前版本 v1'],
      [`${agent}?tab=versions`, '与 v1 对比'],
      [`${agent}?tab=subagents`, '读取报表'],
      [`${agent}/versions/2/edit`, '保存草稿'],
      [`${agent}/compare?base=1&target=2`, '单轮最大推理次数'],
      [`/projects/${project}/environments`, '新建环境'],
      [`/projects/${project}/environments/new`, '创建环境'],
    ];
    await openAs(page, 'developer', pages[0]![0]);
    for (const [url, ready] of pages) {
      await page.goto(url);
      await expect(page.getByText(ready).first()).toBeVisible({ timeout: 20_000 });
      expect(await violations(page), url).toEqual([]);
    }
    // 对话框：发布确认。
    await page.goto(`${agent}/versions/2/edit`);
    await page.getByRole('button', { name: '发布 v2' }).click();
    await expect(page.getByRole('table', { name: 'v1 与 v2 的差异' })).toBeVisible();
    expect(await violations(page), '发布确认').toEqual([]);
  });
}
