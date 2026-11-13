import { expect, test } from '@playwright/test';

import { unique } from './seed';
import { evidencePath, fixtureProject, openAs } from './support';

test.describe('环境', () => {
  test('出网白名单就地校验，修改后修订号递增，只改名字不产生修订（T22-A05）', async ({ page }) => {
    const project = fixtureProject();
    const name = unique('e2e-sandbox');
    await openAs(page, 'developer', `/projects/${project}/environments`);
    await page.getByRole('link', { name: '新建环境' }).first().click();
    await expect(page).toHaveURL(new RegExp(`/projects/${project}/environments/new$`));

    await page.getByRole('textbox', { name: '名称' }).fill(name);
    await page.getByRole('radio', { name: /^沙箱/ }).check();
    await page.getByRole('radio', { name: /^中/ }).check();

    const domain = page.getByRole('textbox', { name: '允许访问的域名' });
    const add = page.getByRole('button', { name: '添加', exact: true });
    const allowlist = page.getByRole('list', { name: '出网白名单' });
    // 不合法的条目在本地被拦截，不加入列表。
    for (const [input, message] of [
      ['https://pypi.org/simple', '不带协议与路径'],
      ['10.0.0.8', '不接受 IP'],
      ['pypi.org:443', '不限定端口'],
    ] as const) {
      await domain.fill(input);
      await add.click();
      await expect(page.getByText(message)).toBeVisible();
      await expect(domain).toHaveAttribute('aria-invalid', 'true');
    }
    await expect(allowlist.getByRole('listitem')).toHaveCount(1); // 只有"没有允许的域名"一行
    // 粘贴多个、大小写混写：拆开、转小写后加入。
    await domain.fill('PyPI.org, *.pythonhosted.org');
    await domain.press('Enter');
    await expect(allowlist).toContainText('pypi.org');
    await expect(allowlist).toContainText('*.pythonhosted.org');
    await domain.fill('pypi.org');
    await add.click();
    await expect(page.getByText('这些域名已经在白名单里。')).toBeVisible();

    await page.getByRole('button', { name: '创建环境' }).click();
    await expect(page).toHaveURL(/\/environments\/env_[^/]+$/);
    await expect(page.getByText('修订 1', { exact: true })).toBeVisible();

    // 改白名单：保存按钮写明新的修订号。
    await domain.fill('files.example.com');
    await add.click();
    await expect(page.getByRole('button', { name: '保存为修订 2' })).toBeVisible();
    await page.getByRole('button', { name: '保存为修订 2' }).click();
    await expect(page.getByText('修订 2', { exact: true })).toBeVisible();
    const history = page.getByRole('table', { name: '修订历史' });
    await expect(history.getByRole('row')).toHaveCount(3);
    await expect(history.getByRole('row', { name: '查看修订 2' })).toContainText('3 个域名');

    // 只改名字：不产生新修订。
    await page.getByRole('textbox', { name: '名称' }).fill(`${name}-renamed`);
    await expect(page.getByRole('button', { name: '保存', exact: true })).toBeVisible();
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await expect(page.getByRole('heading', { name: `编辑 ${name}-renamed`, level: 1 })).toBeVisible();
    await expect(page.getByText('修订 2', { exact: true })).toBeVisible();

    // 查看修订 1：抽屉里写明到当前修订的变化。
    await history.getByRole('row', { name: '查看修订 1' }).click();
    const sheet = page.getByRole('dialog', { name: '修订 1' });
    await expect(sheet).toContainText('到当前修订 2 的变化');
    await expect(sheet).toContainText('files.example.com');
    const shot = evidencePath('environment-revision-light-1280.png');
    if (shot) await page.screenshot({ path: shot });
  });

  test('服务端的校验错误定位到字段：同名的环境', async ({ page }) => {
    const project = fixtureProject();
    const name = unique('e2e-dup');
    await openAs(page, 'developer', `/projects/${project}/environments/new`);
    await page.getByRole('textbox', { name: '名称' }).fill(name);
    await page.getByRole('button', { name: '创建环境' }).click();
    await expect(page).toHaveURL(/\/environments\/env_[^/]+$/);
    await page.goto(`/projects/${project}/environments/new`);
    await page.getByRole('textbox', { name: '名称' }).fill(name);
    await page.getByRole('button', { name: '创建环境' }).click();
    await expect(page.getByText('项目内已有同名的环境，换一个名字。')).toBeVisible();
    await expect(page.getByRole('textbox', { name: '名称' })).toBeFocused();
  });
});
