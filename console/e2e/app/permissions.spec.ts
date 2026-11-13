import { expect, test } from '@playwright/test';

import { evidencePath, fixtureProject, openAs, USERS } from './support';

const REASON = /你在华东经营分析中是项目观察者，不能在 Playground 中对话。请联系项目管理员授予所需的角色。/;

test.describe('权限（T21-A06）', () => {
  test('项目观察者：写操作禁用并说明原因，整页的写入功能说明缺少权限', async ({ page }) => {
    const project = fixtureProject();
    await openAs(page, 'viewer', `/projects/${project}/sessions`);
    await page.getByRole('button', { name: `账号：${USERS.viewer.name}` }).click();
    await expect(page.getByRole('menu')).toContainText('在华东经营分析中是项目观察者');
    await page.keyboard.press('Escape');

    const action = page.getByRole('link', { name: '在 Playground 中对话' });
    await expect(action).toHaveAttribute('aria-disabled', 'true');
    await expect(action).toHaveAccessibleDescription(REASON);
    await action.hover();
    await expect(page.getByRole('tooltip')).toHaveText(REASON);
    const shot = evidencePath('permission-viewer-disabled.png');
    if (shot) await page.screenshot({ path: shot });
    // aria-disabled 的链接对 Playwright 是不可用的：强制点击，确认不会跳转。
    await action.click({ force: true });
    await expect(page).toHaveURL(new RegExp(`/projects/${project}/sessions$`));

    await page.getByRole('navigation', { name: '主导航' }).getByRole('link', { name: 'Playground' }).click();
    await expect(page.getByText('没有权限使用 Playground')).toBeVisible();
    await expect(page.getByRole('status').filter({ hasText: '没有权限使用 Playground' })).toContainText(
      REASON,
    );
    const explain = evidencePath('permission-viewer-explain.png');
    if (explain) await page.screenshot({ path: explain });
  });

  test('项目开发者：同一操作可用', async ({ page }) => {
    const project = fixtureProject();
    await openAs(page, 'developer', `/projects/${project}/sessions`);
    const action = page.getByRole('link', { name: '在 Playground 中对话' });
    await expect(action).not.toHaveAttribute('aria-disabled', 'true');
    await action.click();
    await expect(page).toHaveURL(new RegExp(`/projects/${project}/playground$`));
    await expect(page.getByText('选择已发布的智能体与环境后，在这里直接对话')).toBeVisible();
  });
});
