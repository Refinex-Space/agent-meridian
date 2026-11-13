import path from 'node:path';

import { test, type Page } from '@playwright/test';

import { evidenceDir, openStory, type Theme } from './support';

/**
 * 原型页面的评审截图（T20c，frontend-design.md「设计流程与评审」第 5 步）：每个页面在亮暗两种主题、1280 与 1920 两种宽度下各一张，
 * 各页面的其余状态（加载、空、错误、无权限、长内容、实时更新）在亮色 1280 下各一张。只在设置了 EVIDENCE_DIR 时运行，例如
 * `EVIDENCE_DIR=../tmp/evidence/T20c/round1 pnpm e2e e2e/evidence-prototypes.spec.ts`。
 */
const dir = evidenceDir();
test.skip(dir === null, '未设置 EVIDENCE_DIR，跳过评审截图');
test.describe.configure({ timeout: 120_000 });

interface Shot {
  name: string;
  story: string;
  /** 等到这段文字出现（数据加载完成、play 执行完）再截图。 */
  ready?: string;
  /** 打开后做的操作。 */
  prepare?: (page: Page) => Promise<void>;
  fullPage?: boolean;
}

const PAGES: Shot[] = [
  { name: 'shell', story: '原型-应用外壳--expanded', ready: '门店巡检报告' },
  { name: 'shell-collapsed', story: '原型-应用外壳--collapsed', ready: '门店巡检报告' },
  { name: 'template-list', story: '原型-页面模板--list-template', ready: '门店巡检报告' },
  { name: 'template-detail', story: '原型-页面模板--detail-template', ready: '最近的版本' },
  { name: 'template-edit', story: '原型-页面模板--edit-template', ready: '资源规格' },
  { name: 'template-workbench', story: '原型-页面模板--workbench-template', ready: '本轮结束' },
  { name: 'sessions', story: '原型-会话列表--default', ready: '加载更多' },
  { name: 'pending', story: '原型-待处理--pending', ready: '由业务系统回传' },
  { name: 'pending-detail', story: '原型-待处理--detail', ready: '记住此决定' },
  { name: 'overview', story: '原型-概览--default', ready: '门店巡检报告' },
  { name: 'overview-full', story: '原型-概览--default', ready: '最近失败的轮次', fullPage: true },
  { name: 'session-awaiting', story: '原型-会话详情--awaiting-action', ready: '等待批准' },
];

const STATES: Shot[] = [
  { name: 'shell-budget-alert', story: '原型-应用外壳--budget-alert', ready: '查看用量与预算' },
  { name: 'shell-budget-exhausted', story: '原型-应用外壳--budget-exhausted', ready: '查看用量与预算' },
  { name: 'shell-project-switcher', story: '原型-应用外壳--project-switcher', ready: '集团财务共享' },
  { name: 'shell-command', story: '原型-应用外壳--command-palette', ready: '页面' },
  { name: 'sessions-live', story: '原型-会话列表--live', ready: '条更新' },
  { name: 'sessions-filtered', story: '原型-会话列表--filtered', ready: '门店巡检报告' },
  { name: 'sessions-filtered-empty', story: '原型-会话列表--filtered-empty', ready: '没有匹配的会话' },
  { name: 'sessions-long', story: '原型-会话列表--long-content', ready: '加载更多' },
  { name: 'sessions-first-use', story: '原型-会话列表--first-use', ready: '在 Playground 中对话' },
  {
    name: 'sessions-loading',
    story: '原型-会话列表--loading',
    prepare: (page) => page.waitForTimeout(500),
  },
  { name: 'sessions-error', story: '原型-会话列表--load-failed', ready: '无法加载会话列表' },
  { name: 'sessions-no-permission', story: '原型-会话列表--no-permission', ready: '没有权限查看会话' },
  { name: 'pending-history', story: '原型-待处理--history', ready: '超时视为拒绝' },
  { name: 'pending-clear', story: '原型-待处理--all-clear', ready: '没有等待回应的动作' },
  { name: 'pending-loading', story: '原型-待处理--loading', prepare: (page) => page.waitForTimeout(500) },
  { name: 'pending-error', story: '原型-待处理--load-failed', ready: '无法加载待处理动作' },
  { name: 'pending-no-permission', story: '原型-待处理--no-permission', ready: '入参' },
  { name: 'overview-new', story: '原型-概览--new-project', ready: '还没有用量' },
  { name: 'overview-loading', story: '原型-概览--loading', prepare: (page) => page.waitForTimeout(500) },
  { name: 'overview-partial-error', story: '原型-概览--usage-failed', ready: '无法加载用量与预算' },
  { name: 'overview-no-permission', story: '原型-概览--no-usage-permission', ready: '没有权限查看用量' },
];

async function shoot(page: Page, shot: Shot, theme: Theme, width: number) {
  await page.setViewportSize({ width, height: width === 1920 ? 1080 : 800 });
  await openStory(page, shot.story, theme);
  if (shot.ready) await page.getByText(shot.ready).first().waitFor({ timeout: 20_000 });
  await shot.prepare?.(page);
  // 字体切片与图表在数据到达后才加载，再等一帧。
  await page.waitForTimeout(400);
  await page.screenshot({
    path: path.join(dir!, `${shot.name}-${theme}-${width}.png`),
    fullPage: shot.fullPage ?? false,
    animations: 'disabled',
  });
}

for (const theme of ['light', 'dark'] as Theme[]) {
  for (const width of [1280, 1920]) {
    for (const shot of PAGES) {
      test(`${shot.name} ${theme} ${width}`, async ({ page }) => shoot(page, shot, theme, width));
    }
  }
}

for (const shot of STATES) {
  test(`${shot.name} light 1280`, async ({ page }) => shoot(page, shot, 'light', 1280));
}
