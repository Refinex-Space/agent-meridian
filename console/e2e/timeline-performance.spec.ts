import { expect, test, type Browser, type Page } from '@playwright/test';

import { openStory, writeEvidence } from './support';

/**
 * 性能预算（frontend-design.md「性能预算」）：在固定的 1 万条事件上连续滚动 5 秒，帧时间 p95 不超过 16.7 ms，
 * 没有超过 50 ms 的长任务。
 *
 * 测法（Chrome 性能跟踪）：
 * - 帧时间 = 每一帧里渲染主线程实际忙碌的时间：以 `BeginMainThreadFrame` 切分帧，把主线程上全部任务（`RunTask`、
 *   `ThreadControllerImpl::RunTask`，嵌套的合并为区间并集）截到每一帧的区间里求和。它包含这一帧的脚本、样式、布局与绘制，
 *   p95 不超过 16.7 ms 即 60Hz 下每一帧的工作都放得进一个刷新周期。
 *   不用相邻 requestAnimationFrame 的间隔：60Hz 下它恒等于刷新周期（计时精度 0.1 ms，读数 16.7 或 16.8），量的是显示器；
 *   也不用 `AnimationFrame`（LoAF）区间：连续的动画循环里它从上一帧结束后的第一个任务算起，几乎覆盖整个刷新周期。
 * - 长任务取主线程上超过 50 ms 的任务。
 * - 同时记录 requestAnimationFrame 的间隔，报告掉帧数（间隔超过 1.5 个刷新周期），作为旁证。
 * 滚动按时间匀速推进（每秒 6000 px）；开始前先滚到顶部并等待，排除首次挂载与跳转的开销。
 */

const DURATION_MS = 5000;
const SPEED_PX_PER_MS = 6;

interface TraceEvent {
  name: string;
  ph: string;
  ts: number;
  dur?: number;
  pid: number;
  tid: number;
  id?: string;
  id2?: { local?: string; global?: string };
  args?: { name?: string };
}

async function traced(browser: Browser, page: Page, run: () => Promise<number[]>) {
  await browser.startTracing(page, {
    categories: [
      'devtools.timeline',
      'disabled-by-default-devtools.timeline',
      'disabled-by-default-devtools.timeline.frame',
      'toplevel',
    ],
  });
  const rafIntervals = await run();
  const buffer = await browser.stopTracing();
  const parsed = JSON.parse(buffer.toString()) as TraceEvent[] | { traceEvents: TraceEvent[] };
  const events = Array.isArray(parsed) ? parsed : parsed.traceEvents;
  return { events, rafIntervals };
}

function percentile(values: number[], q: number): number {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.floor(q * sorted.length))] ?? 0;
}

test('1 万条事件连续滚动 5 秒：帧时间 p95 ≤ 16.7 ms，没有超过 50 ms 的长任务', async ({ page, browser }) => {
  await openStory(page, '领域组件-sessiontimeline--ten-thousand-events');
  const list = page.getByRole('list', { name: '事件时间线' });
  await expect(list).toBeVisible();
  await expect(list).toHaveAttribute('style', /height: \d{6,}px/);
  await page.evaluate(async () => {
    const scroller = document.querySelector<HTMLElement>('[role="list"]')?.parentElement;
    if (!scroller) throw new Error('找不到时间轴的滚动容器');
    scroller.scrollTop = 0;
    await new Promise((r) => setTimeout(r, 800));
  });

  const { events, rafIntervals } = await traced(browser, page, () =>
    page.evaluate(
      async ({ duration, speed }) => {
        const scroller = document.querySelector<HTMLElement>('[role="list"]')!.parentElement!;
        const intervals: number[] = [];
        await new Promise<void>((resolve) => {
          let start = 0;
          let previous = 0;
          const step = (now: number) => {
            if (start === 0) start = now;
            else intervals.push(now - previous);
            previous = now;
            scroller.scrollTop = (now - start) * speed;
            if (now - start < duration) requestAnimationFrame(step);
            else resolve();
          };
          requestAnimationFrame(step);
        });
        return intervals;
      },
      { duration: DURATION_MS, speed: SPEED_PX_PER_MS },
    ),
  );

  // 渲染主线程：事件最多的 CrRendererMain（Storybook 的 iframe 与页面同源，同一个渲染进程）。
  const names = new Map<string, string>();
  for (const e of events)
    if (e.ph === 'M' && e.name === 'thread_name' && e.args?.name) names.set(`${e.pid}:${e.tid}`, e.args.name);
  const perThread = new Map<string, number>();
  for (const e of events) {
    const key = `${e.pid}:${e.tid}`;
    if (names.get(key) === 'CrRendererMain') perThread.set(key, (perThread.get(key) ?? 0) + 1);
  }
  const main = [...perThread.entries()].sort((a, b) => b[1] - a[1])[0]?.[0];
  const onMain = events.filter((e) => `${e.pid}:${e.tid}` === main);

  // 主线程任务的区间并集（嵌套的任务合并），用来求每一帧的忙碌时间。
  const intervals = onMain
    .filter((e) => e.ph === 'X' && (e.name === 'RunTask' || e.name === 'ThreadControllerImpl::RunTask'))
    .map((e) => [e.ts, e.ts + (e.dur ?? 0)] as const)
    .sort((a, b) => a[0] - b[0]);
  const busy: [number, number][] = [];
  for (const [start, end] of intervals) {
    const last = busy.at(-1);
    if (last && start <= last[1]) last[1] = Math.max(last[1], end);
    else busy.push([start, end]);
  }
  const frameStarts = onMain
    .filter((e) => e.name === 'BeginMainThreadFrame')
    .map((e) => e.ts)
    .sort((a, b) => a - b);
  const frames: number[] = [];
  for (let i = 0; i + 1 < frameStarts.length; i += 1) {
    const from = frameStarts[i]!;
    const to = frameStarts[i + 1]!;
    let sum = 0;
    for (const [start, end] of busy) {
      if (end <= from) continue;
      if (start >= to) break;
      sum += Math.min(end, to) - Math.max(start, from);
    }
    frames.push(sum / 1000);
  }
  const tasks = busy.map(([start, end]) => (end - start) / 1000);
  const longTasks = tasks.filter((d) => d > 50);
  const refresh = percentile(rafIntervals, 0.5);
  const dropped = rafIntervals.filter((d) => d > refresh * 1.5).length;
  const stats = {
    frames: frames.length,
    frameP50: percentile(frames, 0.5),
    frameP95: percentile(frames, 0.95),
    frameP99: percentile(frames, 0.99),
    frameMax: Math.max(0, ...frames),
    longTasks: longTasks.length,
    longestTask: Math.max(0, ...tasks),
    rafFrames: rafIntervals.length,
    refreshMs: refresh,
    droppedFrames: dropped,
    scrolledPx: DURATION_MS * SPEED_PX_PER_MS,
  };

  const report = [
    '时间轴性能：1 万条事件，连续滚动 5 秒（约 30000 px），Chrome 性能跟踪',
    `帧时间（每帧主线程忙碌时间）：${stats.frames} 帧，p50 ${stats.frameP50.toFixed(2)} ms，p95 ${stats.frameP95.toFixed(2)} ms，p99 ${stats.frameP99.toFixed(2)} ms，最大 ${stats.frameMax.toFixed(2)} ms`,
    `长任务（> 50 ms）：${stats.longTasks} 个，最长任务 ${stats.longestTask.toFixed(2)} ms`,
    `requestAnimationFrame：${stats.rafFrames} 次，刷新周期 ${stats.refreshMs.toFixed(2)} ms，掉帧 ${stats.droppedFrames} 次`,
  ].join('\n');
  console.log(report);
  writeEvidence('timeline-performance.txt', `${report}\n\n${JSON.stringify(stats, null, 2)}\n`);

  expect(stats.frames).toBeGreaterThan(200);
  expect(stats.frameP95).toBeLessThanOrEqual(16.7);
  expect(stats.longTasks).toBe(0);
});
