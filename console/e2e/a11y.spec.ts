import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

import { openStory, type Theme } from './support';

/**
 * Storybook 的 a11y 检查不得有违规（frontend-design.md「测试」）：用 axe 按 WCAG 2.2 AA 检查每个故事的亮暗两套主题，
 * 含颜色对比度。原型的故事等到数据加载完成再检查。
 */
const STORIES: { id: string; ready: string | null }[] = [
  { id: '基础-令牌--tokens', ready: null },
  { id: '基础-图标--icons', ready: null },
  { id: '领域组件-sessiontimeline--finished', ready: null },
  { id: '领域组件-sessiontimeline--awaiting-action', ready: null },
  { id: '领域组件-sessiontimeline--subthreads-running', ready: null },
  { id: '原型-会话详情--awaiting-action', ready: '等待批准' },
  { id: '原型-会话详情--finished', ready: '本轮结束' },
  { id: '原型-会话详情--load-failed', ready: '无法打开这个会话' },
];

for (const theme of ['light', 'dark'] as Theme[]) {
  for (const { id, ready } of STORIES) {
    test(`${id}（${theme === 'light' ? '亮色' : '暗色'}）没有无障碍违规`, async ({ page }) => {
      await openStory(page, id, theme);
      if (ready) await page.getByText(ready).first().waitFor();
      const results = await new AxeBuilder({ page })
        .include('#storybook-root')
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
        .analyze();
      const violations = results.violations.map((v) => ({
        id: v.id,
        impact: v.impact,
        targets: v.nodes.slice(0, 5).map((n) => n.target.join(' ')),
      }));
      expect(violations).toEqual([]);
    });
  }
}
