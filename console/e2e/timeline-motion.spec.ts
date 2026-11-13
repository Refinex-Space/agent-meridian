import { expect, test, type Page } from '@playwright/test';

import { openStory } from './support';

/**
 * 时间轴的编排动效（frontend-design.md「动效」）：新事件沿子午线落位（200ms，ease-out），"此刻"标记以 2 秒周期呼吸，
 * 新事件到达时"此刻"随之下移；减少动效时位移全部取消，只保留不透明度变化，呼吸静止。
 */

/** 读出某个关键帧里出现的属性。 */
function keyframeProperties(page: Page, name: string): Promise<string[]> {
  return page.evaluate((keyframes) => {
    const props = new Set<string>();
    const visit = (rules: CSSRuleList) => {
      for (const rule of Array.from(rules)) {
        if (rule instanceof CSSKeyframesRule && rule.name === keyframes) {
          for (const frame of Array.from(rule.cssRules) as CSSKeyframeRule[]) {
            for (const prop of Array.from(frame.style)) props.add(prop);
          }
        } else if ('cssRules' in rule && rule.cssRules) {
          visit(rule.cssRules as CSSRuleList);
        }
      }
    };
    for (const sheet of Array.from(document.styleSheets)) visit(sheet.cssRules);
    return [...props].sort();
  }, name);
}

/** 等到一行新事件到达，返回它的动画名、时长与缓动。 */
async function arrivingRow(page: Page) {
  const row = page.locator('[role="listitem"] > div[style*="animation-delay"]').first();
  await row.waitFor({ timeout: 10_000 });
  return row.evaluate((el) => {
    const style = getComputedStyle(el);
    return {
      name: style.animationName,
      duration: style.animationDuration,
      easing: style.animationTimingFunction,
    };
  });
}

async function nowMarker(page: Page) {
  const diamond = page.locator('[aria-live="polite"] svg path').first();
  await diamond.waitFor();
  const item = page.locator('[role="listitem"]').filter({ has: page.locator('[aria-live="polite"]') });
  return {
    breath: await diamond.evaluate((el) => getComputedStyle(el).animationName),
    glide: await item.evaluate((el) => getComputedStyle(el).transitionProperty),
  };
}

test('新事件落位：位移加不透明度 200ms ease-out；"此刻"呼吸并随新事件下移', async ({ page }) => {
  await openStory(page, '领域组件-sessiontimeline--live');
  const arrival = await arrivingRow(page);
  expect(arrival).toEqual({ name: 'arrive', duration: '0.2s', easing: 'cubic-bezier(0.2, 0, 0, 1)' });
  expect(await keyframeProperties(page, 'arrive')).toEqual(['opacity', 'transform']);
  const marker = await nowMarker(page);
  expect(marker.breath).toBe('breath');
  expect(marker.glide.split(', ')).toContain('transform');
});

test('减少动效：落位只有不透明度变化，"此刻"静止且不滑动', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await openStory(page, '领域组件-sessiontimeline--live');
  const arrival = await arrivingRow(page);
  expect(arrival.name).toBe('fade-in');
  expect(await keyframeProperties(page, 'fade-in')).toEqual(['opacity']);
  const marker = await nowMarker(page);
  expect(marker.breath).toBe('none');
  expect(marker.glide).toBe('none');
});
