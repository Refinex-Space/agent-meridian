import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

import { openStory, writeEvidence, type Theme } from './support';

/**
 * Storybook 的 a11y 检查不得有违规（frontend-design.md「测试」）：用 axe 按 WCAG 2.2 AA 检查每个故事的亮暗两套主题，
 * 含颜色对比度。原型的故事等到数据加载完成再检查（下面的列表）；其余故事从构建产物的 index.json 读出全部。
 * 都检查整个页面（对话框、菜单、提示渲染在 Portal 里，不在 #storybook-root 中）。
 */
const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'];
const STORIES: { id: string; ready: string | null }[] = [
  { id: '基础-令牌--tokens', ready: null },
  { id: '基础-图标--icons', ready: null },
  { id: '领域组件-sessiontimeline--finished', ready: null },
  { id: '领域组件-sessiontimeline--awaiting-action', ready: null },
  { id: '领域组件-sessiontimeline--subthreads-running', ready: null },
  { id: '原型-会话详情--awaiting-action', ready: '等待批准' },
  { id: '原型-会话详情--finished', ready: '本轮结束' },
  { id: '原型-会话详情--load-failed', ready: '无法打开这个会话' },
  { id: '原型-应用外壳--expanded', ready: '门店巡检报告' },
  { id: '原型-应用外壳--collapsed', ready: '门店巡检报告' },
  { id: '原型-应用外壳--budget-alert', ready: '查看用量与预算' },
  { id: '原型-应用外壳--command-palette', ready: '页面' },
  { id: '原型-页面模板--list-template', ready: '门店巡检报告' },
  { id: '原型-页面模板--detail-template', ready: '最近的版本' },
  { id: '原型-页面模板--edit-template', ready: '资源规格' },
  { id: '原型-页面模板--workbench-template', ready: '本轮结束' },
  { id: '原型-会话列表--default', ready: '加载更多' },
  { id: '原型-会话列表--long-content', ready: '加载更多' },
  { id: '原型-会话列表--first-use', ready: '在 Playground 中对话' },
  { id: '原型-会话列表--load-failed', ready: '无法加载会话列表' },
  { id: '原型-会话列表--no-permission', ready: '没有权限查看会话' },
  { id: '原型-待处理--pending', ready: '由业务系统回传' },
  { id: '原型-待处理--detail', ready: '记住此决定' },
  { id: '原型-待处理--history', ready: '超时视为拒绝' },
  { id: '原型-待处理--no-permission', ready: '入参' },
  { id: '原型-概览--default', ready: '门店巡检报告' },
  { id: '原型-概览--new-project', ready: '还没有用量' },
  { id: '原型-概览--no-usage-permission', ready: '没有权限查看用量' },
];

for (const theme of ['light', 'dark'] as Theme[]) {
  for (const { id, ready } of STORIES) {
    test(`${id}（${theme === 'light' ? '亮色' : '暗色'}）没有无障碍违规`, async ({ page }) => {
      await openStory(page, id, theme);
      if (ready) await page.getByText(ready).first().waitFor();
      // 预览里的 a11y 插件在渲染后也跑一次 axe，同时运行会报"Axe is already running"：与下面的全量检查一样先等它跑完。
      await page.waitForTimeout(400);
      // 整个页面：抽屉、对话框、命令面板渲染在 Portal 里，不在 #storybook-root 中。
      const results = await new AxeBuilder({ page }).withTags(TAGS).analyze();
      const violations = results.violations.map((v) => ({
        id: v.id,
        impact: v.impact,
        targets: v.nodes.slice(0, 5).map((n) => n.target.join(' ')),
      }));
      expect(violations).toEqual([]);
    });
  }
}

interface StoryIndex {
  entries: Record<string, { id: string; type: 'story' | 'docs'; title: string; name: string }>;
}

for (const theme of ['light', 'dark'] as Theme[]) {
  test(`全部基元、领域组件与基础页的故事（${theme === 'light' ? '亮色' : '暗色'}）没有无障碍违规`, async ({
    page,
    request,
  }) => {
    test.setTimeout(900_000);
    const index = (await (await request.get('/index.json')).json()) as StoryIndex;
    const ids = Object.values(index.entries)
      .filter((e) => e.type === 'story' && !e.id.startsWith('原型-'))
      .map((e) => e.id)
      .sort();
    const failures: {
      id: string;
      violations: { id: string; impact: string | null | undefined; targets: string[] }[];
    }[] = [];
    for (const id of ids) {
      await openStory(page, id, theme);
      // 带 play 的故事（展开菜单、点击复制、提交确认）在渲染后执行，等它进入要检查的状态。
      await page.waitForTimeout(400);
      const results = await new AxeBuilder({ page }).withTags(TAGS).analyze();
      if (results.violations.length > 0) {
        failures.push({
          id,
          violations: results.violations.map((v) => ({
            id: v.id,
            impact: v.impact,
            targets: v.nodes.slice(0, 5).map((n) => n.target.join(' ')),
          })),
        });
      }
    }
    writeEvidence(
      `a11y-${theme}.json`,
      JSON.stringify({ theme, tags: TAGS, checked: ids.length, stories: ids, failures }, null, 2),
    );
    expect(failures).toEqual([]);
  });
}
