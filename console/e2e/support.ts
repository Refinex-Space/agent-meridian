import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';

import type { Page } from '@playwright/test';

export type Theme = 'light' | 'dark';

/** 打开一个故事（不带 Storybook 的外框），等字体加载完成。 */
export async function openStory(page: Page, id: string, theme: Theme = 'light'): Promise<void> {
  await page.goto(`/iframe.html?id=${encodeURIComponent(id)}&viewMode=story&globals=theme:${theme}`);
  await page.locator('#storybook-root > *').first().waitFor();
  await page.evaluate(() => document.fonts.ready);
}

/** 评审证据目录：设置了 EVIDENCE_DIR 时写入（例如 ../tmp/evidence/T20a）。 */
export function evidenceDir(): string | null {
  const dir = process.env['EVIDENCE_DIR'];
  if (!dir) return null;
  const resolved = path.resolve(dir);
  mkdirSync(resolved, { recursive: true });
  return resolved;
}

export function writeEvidence(name: string, content: string): void {
  const dir = evidenceDir();
  if (dir) writeFileSync(path.join(dir, name), content);
}
