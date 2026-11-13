import { expect, test, type Page } from '@playwright/test';

import { unique } from './seed';
import { fixtureProject, openAs } from './support';

/** 页面上所有输入框当前的值（包括已关闭的对话框里还在 DOM 中的）。 */
const inputValues = (page: Page) =>
  page.evaluate(() => Array.from(document.querySelectorAll('input, textarea')).map((el) => (el as HTMLInputElement).value));

/** 浏览器存储的全部内容：localStorage、sessionStorage、IndexedDB 的库名、Cookie 与 Cache Storage 的键。 */
async function storageText(page: Page): Promise<string> {
  const inPage = await page.evaluate(async () => {
    const dump = (s: Storage) => Object.fromEntries(Array.from({ length: s.length }, (_, i) => s.key(i)!).map((k) => [k, s.getItem(k)]));
    const databases = (await indexedDB.databases()).map((d) => d.name);
    const caches = 'caches' in window ? await window.caches.keys() : [];
    return { local: dump(localStorage), session: dump(sessionStorage), databases, caches };
  });
  const cookies = await page.context().cookies();
  return JSON.stringify({ ...inPage, cookies });
}

test.describe('凭据', () => {
  test('写入与轮换后，明文不出现在页面、网络响应与浏览器存储中（T23-A04）', async ({ page }) => {
    const name = unique('e2e-secret');
    const first = `sk-e2e-${unique('first')}-PLAINTEXT`;
    const second = `sk-e2e-${unique('second')}-PLAINTEXT`;

    // 记录全部响应的正文与请求的正文：明文只能出现在写入与轮换的那两个请求体里。
    const responses: Promise<string>[] = [];
    const requestsWithPlaintext: string[] = [];
    page.on('response', (response) => {
      responses.push(response.text().catch(() => ''));
    });
    page.on('request', (request) => {
      const body = request.postData() ?? '';
      if (body.includes(first) || body.includes(second)) requestsWithPlaintext.push(`${request.method()} ${new URL(request.url()).pathname}`);
    });

    await openAs(page, 'admin', `/projects/${fixtureProject()}/settings`);
    await expect(page.getByRole('heading', { name: '设置', level: 1 })).toBeVisible();
    await expect(page.getByRole('tab', { name: '凭据', selected: true })).toBeVisible();

    await page.getByRole('button', { name: '写入凭据' }).first().click();
    const dialog = page.getByRole('dialog', { name: '写入凭据' });
    await dialog.getByRole('textbox', { name: '名称' }).fill(name);
    const value = dialog.getByLabel('凭据值', { exact: true });
    await expect(value).toHaveAttribute('type', 'password');
    await value.fill(first);
    await dialog.getByRole('button', { name: '保存凭据' }).click();
    await expect(dialog).toBeHidden();

    const row = page.getByRole('row').filter({ hasText: name });
    await expect(row).toContainText('租户内通用');
    await expect(row).toContainText('未被引用');
    await expect(row).toContainText('从未使用');

    // 轮换：版本号加一，旧值不再出现在任何地方。
    await row.getByRole('button', { name: `${name} 的更多操作` }).click();
    await page.getByRole('menuitem', { name: '轮换' }).click();
    const rotate = page.getByRole('dialog', { name: `轮换 ${name}` });
    await rotate.getByLabel('新的凭据值', { exact: true }).fill(second);
    await rotate.getByRole('button', { name: '轮换为版本 2' }).click();
    await expect(rotate).toBeHidden();
    await expect(row.getByRole('cell').nth(2)).toHaveText('2');

    // 刷新后从服务端重读：仍然只有元数据。
    await page.reload();
    await expect(page.getByRole('row').filter({ hasText: name })).toBeVisible();

    for (const plaintext of [first, second]) {
      expect(await page.content()).not.toContain(plaintext);
      expect(await inputValues(page)).not.toContain(plaintext);
      expect(await storageText(page)).not.toContain(plaintext);
      for (const body of await Promise.all(responses)) expect(body).not.toContain(plaintext);
    }
    expect(requestsWithPlaintext).toEqual([
      expect.stringMatching(/^POST \/admin\/v1\/tenants\/ten_[^/]+\/secrets$/),
      expect.stringMatching(/^POST \/admin\/v1\/tenants\/ten_[^/]+\/secrets\/sec_[^/]+:rotate$/),
    ]);

    // 没有引用方的凭据可以删除：输入名称确认。
    await page.getByRole('row').filter({ hasText: name }).getByRole('button', { name: `${name} 的更多操作` }).click();
    await page.getByRole('menuitem', { name: '删除' }).click();
    const confirm = page.getByRole('alertdialog', { name: `删除凭据 ${name}` });
    await expect(confirm).toContainText('不能恢复');
    await confirm.getByRole('textbox').fill(name);
    await confirm.getByRole('button', { name: '删除凭据' }).click();
    await expect(page.getByRole('row').filter({ hasText: name })).toHaveCount(0);
  });

  test('没有凭据权限的成员看到原因，看不到任何凭据', async ({ page }) => {
    await openAs(page, 'developer', `/projects/${fixtureProject()}/settings`);
    await expect(page.getByText('没有权限查看凭据')).toBeVisible();
    await expect(page.getByRole('table')).toHaveCount(0);
  });
});
