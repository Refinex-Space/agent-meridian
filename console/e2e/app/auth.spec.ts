import { expect, test, type Page, type Request } from '@playwright/test';

import { expectLoginPage, fixtureProject, KEYCLOAK, openAs, signIn, USERS } from './support';

/** 下一个调用管理 API 的请求带的 access token。 */
async function nextBearer(page: Page, action: () => Promise<unknown>): Promise<string> {
  const [request] = await Promise.all([
    page.waitForRequest(
      (r: Request) => r.url().includes('/admin/v1/') && r.headers()['authorization'] !== undefined,
    ),
    action(),
  ]);
  return (request.headers()['authorization'] ?? '').replace(/^Bearer /, '');
}

/** 浏览器存储（localStorage、sessionStorage、Cookie）中的全部内容。 */
async function storedValues(page: Page): Promise<string> {
  const web = await page.evaluate(() => {
    const dump = (s: Storage) =>
      Array.from({ length: s.length }, (_, i) => `${s.key(i)}=${s.getItem(s.key(i)!)}`);
    return [...dump(window.localStorage), ...dump(window.sessionStorage)].join('\n');
  });
  const cookies = (await page.context().cookies('http://localhost:5173')).map((c) => `${c.name}=${c.value}`);
  return [web, ...cookies].join('\n');
}

test.describe('登录（T21-A01）', () => {
  test('未登录访问受保护页面时跳转登录，登录后回到原页面', async ({ page }) => {
    const target = `/projects/${fixtureProject()}/agents`;
    await page.goto(target);
    await signIn(page, 'developer');
    await expect(page).toHaveURL(new RegExp(`${target}$`));
    await expect(page.getByRole('heading', { name: '智能体', level: 1 })).toBeVisible();
    await expect(page.getByRole('button', { name: `账号：${USERS.developer.name}` })).toBeVisible();
  });

  test('令牌只在内存中：浏览器存储里没有令牌；刷新页面后静默续期，不经过登录页', async ({ page }) => {
    const target = `/projects/${fixtureProject()}/overview`;
    const first = await nextBearer(page, () => openAs(page, 'developer', target));
    expect(first.split('.')).toHaveLength(3);
    const stored = await storedValues(page);
    expect(stored).not.toContain(first);
    expect(stored).not.toMatch(/access_token|refresh_token|id_token/);

    // 刷新：内存中的令牌丢失，经隐藏的 iframe 向 Keycloak 静默登录；主页面不离开控制台。
    const mainFrameUrls: string[] = [];
    page.on('framenavigated', (frame) => {
      if (frame === page.mainFrame()) mainFrameUrls.push(frame.url());
    });
    const renewed = await nextBearer(page, () => page.reload());
    await expect(page.getByRole('heading', { name: '概览', level: 1 })).toBeVisible();
    expect(renewed).not.toBe(first);
    expect(mainFrameUrls.filter((url) => url.startsWith(KEYCLOAK))).toEqual([]);
    expect(await storedValues(page)).not.toContain(renewed);
  });

  test('退出登录后回到登录页，受保护页面再次跳转登录', async ({ page }) => {
    const target = `/projects/${fixtureProject()}/sessions`;
    await openAs(page, 'developer', target);
    await page.getByRole('button', { name: `账号：${USERS.developer.name}` }).click();
    await page.getByRole('menuitem', { name: '退出登录' }).click();
    await expectLoginPage(page);
    await page.goto(target);
    await expectLoginPage(page);
  });
});
