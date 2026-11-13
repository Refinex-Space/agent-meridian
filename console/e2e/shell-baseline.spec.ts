import { expect, test } from '@playwright/test';

import { openStory, type Theme } from './support';

/**
 * 应用外壳与四种页面模板的截图基线（T21-A07，frontend-design.md「测试」）：亮暗两种主题、1280 与 1920 两种宽度。
 * 故事是 T20c 的原型（`原型/应用外壳`、`原型/页面模板`），外壳已换成正式版本（`app/shell/app-shell.tsx`），T21 建立基线时与
 * T20c 的截图逐像素一致。之后的页面任务只允许内容区与基线不同：改外壳或模板时更新基线（`--update-snapshots`），并在评审中说明。
 *
 * 夹具的时间相对"现在"生成（相对时间、日期），截图前把页面时钟固定在同一时刻。基线按平台分开（`-darwin`、`-linux`）：
 * CI 用的 Linux 基线在 Playwright 官方镜像中生成，见 console/CLAUDE.md。
 */
const NOW = new Date('2026-11-13T10:30:00+08:00');

const SHOTS = [
  { name: 'shell', story: '原型-应用外壳--expanded', ready: '门店巡检报告' },
  { name: 'shell-collapsed', story: '原型-应用外壳--collapsed', ready: '门店巡检报告' },
  { name: 'template-list', story: '原型-页面模板--list-template', ready: '门店巡检报告' },
  { name: 'template-detail', story: '原型-页面模板--detail-template', ready: '最近的版本' },
  { name: 'template-edit', story: '原型-页面模板--edit-template', ready: '资源规格' },
  { name: 'template-workbench', story: '原型-页面模板--workbench-template', ready: '本轮结束' },
] as const;

test.describe.configure({ timeout: 60_000 });

for (const theme of ['light', 'dark'] as Theme[]) {
  for (const width of [1280, 1920]) {
    for (const shot of SHOTS) {
      test(`${shot.name} ${theme} ${width}`, async ({ page }) => {
        await page.clock.setFixedTime(NOW);
        await page.setViewportSize({ width, height: width === 1920 ? 1080 : 800 });
        await openStory(page, shot.story, theme);
        await page.getByText(shot.ready).first().waitFor();
        await expect(page).toHaveScreenshot(`${shot.name}-${theme}-${width}.png`, { animations: 'disabled' });
      });
    }
  }
}
