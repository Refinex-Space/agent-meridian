import { expect, test, type Page } from '@playwright/test';

import { fixtureProject, openAs, USERS } from './support';

async function chooseTheme(page: Page, label: '亮色' | '暗色' | '跟随系统'): Promise<void> {
  await page.getByRole('button', { name: `账号：${USERS.developer.name}` }).click();
  await page.getByRole('menuitemradio', { name: label }).click();
}

const html = (page: Page) => page.locator('html');

test.describe('主题', () => {
  test('默认跟随系统，随系统切换', async ({ page }) => {
    await page.emulateMedia({ colorScheme: 'dark' });
    await openAs(page, 'developer', `/projects/${fixtureProject()}/overview`);
    await expect(html(page)).toHaveAttribute('data-theme', 'dark');
    await page.emulateMedia({ colorScheme: 'light' });
    await expect(html(page)).toHaveAttribute('data-theme', 'light');
  });

  test('选择亮色或暗色后记在本地，刷新后首次绘制即为所选主题，不随系统变化', async ({ page }) => {
    await page.emulateMedia({ colorScheme: 'light' });
    await openAs(page, 'developer', `/projects/${fixtureProject()}/overview`);
    await chooseTheme(page, '暗色');
    await expect(html(page)).toHaveAttribute('data-theme', 'dark');
    await expect(page.locator('body')).toHaveCSS('background-color', /.+/);

    // 首次绘制前的内联脚本读本地设置：在应用代码执行前检查一次。
    await page.addInitScript(() => {
      document.addEventListener('DOMContentLoaded', () => {
        document.documentElement.dataset['firstPaintTheme'] = document.documentElement.dataset['theme'] ?? '';
      });
    });
    await page.reload();
    await expect(html(page)).toHaveAttribute('data-first-paint-theme', 'dark');
    await expect(html(page)).toHaveAttribute('data-theme', 'dark');
    await page.emulateMedia({ colorScheme: 'light' });
    await expect(html(page)).toHaveAttribute('data-theme', 'dark');

    await chooseTheme(page, '亮色');
    await expect(html(page)).toHaveAttribute('data-theme', 'light');
    await page.emulateMedia({ colorScheme: 'dark' });
    await expect(html(page)).toHaveAttribute('data-theme', 'light');

    await chooseTheme(page, '跟随系统');
    await expect(html(page)).toHaveAttribute('data-theme', 'dark');
    expect(await page.evaluate(() => window.localStorage.getItem('meridian.theme'))).toBeNull();
  });
});
