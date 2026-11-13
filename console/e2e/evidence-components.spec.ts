import path from 'node:path';

import { test } from '@playwright/test';

import { evidenceDir, openStory, type Theme } from './support';

/**
 * T20b 的评审截图：基元与领域组件在亮暗两种主题下的样子，对照 T20a 确认的方向（同一套颜色、字体、间距、圆角、阴影）。
 * 只在设置了 EVIDENCE_DIR 时运行：`EVIDENCE_DIR=../tmp/evidence/T20b pnpm e2e e2e/evidence-components.spec.ts`。
 */
const dir = evidenceDir();
test.skip(dir === null, '未设置 EVIDENCE_DIR，跳过评审截图');
test.describe.configure({ timeout: 120_000 });

const THEMES: Theme[] = ['light', 'dark'];

interface Shot {
  name: string;
  story: string;
  widths: readonly number[];
  fullPage?: boolean;
}

const SHOTS: Shot[] = [
  { name: 'primitives-overview', story: '基元-总览--all', widths: [1280, 1920], fullPage: true },
  { name: 'domain-overview', story: '领域组件-总览--all', widths: [1280, 1920], fullPage: true },
  { name: 'icons-illustrations', story: '基础-图标--icons', widths: [1280], fullPage: true },
  { name: 'data-table', story: '领域组件-datatable--default', widths: [1280, 1920] },
  { name: 'data-table-bulk', story: '领域组件-datatable--bulk-actions', widths: [1280] },
  { name: 'data-table-loading', story: '领域组件-datatable--loading', widths: [1280] },
  { name: 'filter-bar-open', story: '领域组件-filterbar--open', widths: [1280] },
  { name: 'dialog', story: '基元-对话框--default', widths: [1280] },
  { name: 'danger-confirm-name', story: '领域组件-dangerconfirmdialog--confirm-name', widths: [1280] },
  { name: 'danger-confirm-error', story: '领域组件-dangerconfirmdialog--error', widths: [1280] },
  { name: 'sheet', story: '基元-抽屉--default', widths: [1280] },
  { name: 'dropdown', story: '基元-下拉菜单--selection', widths: [1280] },
  { name: 'select-open', story: '基元-选择器--open', widths: [1280] },
  { name: 'command', story: '基元-命令面板--default', widths: [1280] },
  { name: 'toast-error', story: '基元-通知--error', widths: [1280] },
  { name: 'tooltip', story: '基元-提示--default', widths: [1280] },
  { name: 'inspector', story: '领域组件-inspectorpanel--default', widths: [1280] },
  { name: 'tool-call-awaiting', story: '领域组件-toolcallview--awaiting', widths: [1280] },
  { name: 'empty-error-states', story: '领域组件-errorstate--validation', widths: [1280] },
];

for (const theme of THEMES) {
  for (const shot of SHOTS) {
    for (const width of shot.widths) {
      test(`${shot.name} ${theme} ${width}`, async ({ page }) => {
        await page.setViewportSize({ width, height: width === 1920 ? 1080 : 800 });
        await openStory(page, shot.story, theme);
        // 带 play 的故事（展开菜单、提交确认）与浮层的进入动画结束后再截图。
        await page.waitForTimeout(800);
        await page.screenshot({
          path: path.join(dir!, `${shot.name}-${theme}-${width}.png`),
          fullPage: shot.fullPage ?? false,
          animations: 'disabled',
        });
      });
    }
  }
}
