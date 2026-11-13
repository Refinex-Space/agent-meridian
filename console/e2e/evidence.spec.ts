import path from 'node:path';

import { test, type Page } from '@playwright/test';

import { evidenceDir, openStory, type Theme } from './support';

/**
 * 评审截图（frontend-design.md「设计流程与评审」第 5 步）：亮暗两种主题、1280 与 1920 两种宽度，
 * 外加时间轴在常规与减少动效下的录屏。只在设置了 EVIDENCE_DIR 时运行，例如
 * `EVIDENCE_DIR=../tmp/evidence/T20a pnpm e2e e2e/evidence.spec.ts`。
 */
const dir = evidenceDir();
test.skip(dir === null, '未设置 EVIDENCE_DIR，跳过评审截图');
test.describe.configure({ timeout: 180_000 });

const THEMES: Theme[] = ['light', 'dark'];
const WIDTHS = [1280, 1920] as const;

interface Shot {
  name: string;
  story: string;
  fullPage?: boolean;
  /** 打开后做的操作，等到页面进入要截的状态。 */
  prepare?: (page: Page) => Promise<void>;
}

const SHOTS: Shot[] = [
  { name: 'tokens', story: '基础-令牌--tokens', fullPage: true },
  { name: 'icons', story: '基础-图标--icons', fullPage: true },
  { name: 'timeline-subthreads', story: '领域组件-sessiontimeline--subthreads-running' },
  {
    name: 'session-awaiting',
    story: '原型-会话详情--awaiting-action',
    prepare: async (page) => {
      await page.getByRole('button', { name: '批准', exact: true }).waitFor();
    },
  },
  {
    name: 'session-inspector',
    story: '原型-会话详情--finished',
    prepare: async (page) => {
      await page.getByText('本轮结束').first().waitFor();
      await page.getByRole('button', { name: /推理 qwen-plus 2\.8 秒/ }).click();
      await page.getByRole('heading', { name: '上下文构成' }).waitFor();
    },
  },
  {
    name: 'session-live',
    story: '原型-会话详情--live',
    prepare: async (page) => {
      await page.getByRole('button', { name: '批准', exact: true }).click({ timeout: 30_000 });
      await page.getByText('正在回复').waitFor({ timeout: 15_000 });
      await page.waitForTimeout(1500);
    },
  },
  {
    name: 'session-loading',
    story: '原型-会话详情--loading',
    prepare: async (page) => {
      await page.getByLabel('正在加载事件').waitFor();
      await page.waitForTimeout(400);
    },
  },
];

for (const theme of THEMES) {
  for (const width of WIDTHS) {
    for (const shot of SHOTS) {
      test(`${shot.name} ${theme} ${width}`, async ({ page }) => {
        await page.setViewportSize({ width, height: width === 1920 ? 1080 : 800 });
        await openStory(page, shot.story, theme);
        await shot.prepare?.(page);
        await page.screenshot({
          path: path.join(dir!, `${shot.name}-${theme}-${width}.png`),
          fullPage: shot.fullPage ?? false,
          animations: shot.name === 'session-live' ? 'allow' : 'disabled',
        });
      });
    }
  }
}

for (const reduced of [false, true]) {
  test(`时间轴录屏（${reduced ? '减少动效' : '常规动效'}）`, async ({ browser }) => {
    const context = await browser.newContext({
      viewport: { width: 1280, height: 800 },
      timezoneId: 'Asia/Shanghai',
      reducedMotion: reduced ? 'reduce' : 'no-preference',
      recordVideo: { dir: path.join(dir!, 'video-tmp'), size: { width: 1280, height: 800 } },
    });
    const page = await context.newPage();
    await openStory(page, '原型-会话详情--live');
    await page.getByRole('button', { name: '批准', exact: true }).click({ timeout: 30_000 });
    await page.getByText('本轮结束').nth(1).waitFor({ timeout: 30_000 });
    await page.waitForTimeout(1000);
    const video = page.video();
    await context.close();
    await video?.saveAs(path.join(dir!, `timeline-${reduced ? 'reduced-motion' : 'motion'}.webm`));
    await video?.delete();
  });
}
