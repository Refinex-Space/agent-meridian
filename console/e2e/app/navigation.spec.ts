import { expect, test, type Page } from '@playwright/test';

import { evidencePath, fixtureProject, openAs } from './support';

/** 架构设计「控制台·信息架构」的十一个一级导航，按顺序。 */
const NAV = [
  ['概览', 'overview'],
  ['会话', 'sessions'],
  ['Playground', 'playground'],
  ['智能体', 'agents'],
  ['环境', 'environments'],
  ['能力', 'capabilities'],
  ['知识库', 'knowledge'],
  ['模型', 'models'],
  ['待处理', 'pending'],
  ['用量与预算', 'usage'],
  ['设置', 'settings'],
] as const;

const nav = (page: Page) => page.getByRole('navigation', { name: '主导航' });

test.describe('导航（T21-A02）', () => {
  test('导航结构与架构设计一致，点击进入对应页面，当前项与面包屑随之变化', async ({ page }) => {
    const project = fixtureProject();
    await openAs(page, 'developer', `/projects/${project}/overview`);
    await expect(nav(page).getByRole('link')).toHaveText(NAV.map(([label]) => label));
    for (const [label, path] of NAV) {
      await nav(page).getByRole('link', { name: label }).click();
      await expect(page).toHaveURL(new RegExp(`/projects/${project}/${path}$`));
      await expect(page.getByRole('heading', { name: label, level: 1 })).toBeVisible();
      await expect(nav(page).getByRole('link', { name: label })).toHaveAttribute('aria-current', 'page');
      await expect(page.getByRole('navigation', { name: '面包屑' })).toHaveText(label);
    }
  });

  test('收起导航后刷新页面仍是收起状态', async ({ page }) => {
    await openAs(page, 'developer', `/projects/${fixtureProject()}/agents`);
    await page.getByRole('button', { name: '收起导航' }).click();
    await expect(nav(page)).toHaveCSS('width', '56px');
    await page.reload();
    await expect(page.getByRole('button', { name: '展开导航' })).toBeVisible();
    await expect(nav(page)).toHaveCSS('width', '56px');
    const shot = evidencePath('shell-collapsed-light-1280.png');
    if (shot) await page.screenshot({ path: shot });
    await page.getByRole('button', { name: '展开导航' }).click();
    await expect(nav(page)).toHaveCSS('width', '232px');
  });

  test('⌘K 命令面板能跳转到任一页面', async ({ page }) => {
    const project = fixtureProject();
    await openAs(page, 'developer', `/projects/${project}/overview`);
    for (const [label, path] of NAV) {
      await page.keyboard.press('ControlOrMeta+k');
      const dialog = page.getByRole('dialog');
      await dialog.getByRole('combobox').fill(label);
      await dialog.getByRole('option', { name: label }).click();
      await expect(dialog).toBeHidden();
      await expect(page).toHaveURL(new RegExp(`/projects/${project}/${path}$`));
      await expect(page.getByRole('heading', { name: label, level: 1 })).toBeVisible();
    }
    // 回车选中第一项。
    await page.getByRole('button', { name: /搜索/ }).click();
    await page.getByRole('dialog').getByRole('combobox').fill('知识库');
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(new RegExp(`/projects/${project}/knowledge$`));
  });

  test('切换项目后停在同一个一级导航，下次打开首页回到这个项目', async ({ page }) => {
    const [main, other] = [fixtureProject('main'), fixtureProject('other')];
    await openAs(page, 'developer', `/projects/${main}/environments`);
    await page.getByRole('button', { name: '切换项目：华东经营分析' }).click();
    await page.getByRole('menuitemradio', { name: '门店运营' }).click();
    await expect(page).toHaveURL(new RegExp(`/projects/${other}/environments$`));
    await expect(page.getByRole('button', { name: '切换项目：门店运营' })).toBeVisible();
    await page.goto('/');
    await expect(page).toHaveURL(new RegExp(`/projects/${other}/overview$`));
  });

  test('项目内不存在的页面与不能进入的项目给出说明', async ({ page }) => {
    const project = fixtureProject();
    await openAs(page, 'developer', `/projects/${project}/no-such-page`);
    await expect(page.getByText('这个页面不存在')).toBeVisible();
    await page.goto('/projects/prj_00000000000000000000000000/overview');
    await expect(page.getByText('这个项目不存在，或你没有进入它的权限')).toBeVisible();
    await page.getByRole('link', { name: '回到首页' }).click();
    await expect(page).toHaveURL(/\/projects\/prj_[^/]+\/overview$/);
  });
});

test.describe('外壳截图', () => {
  for (const width of [1280, 1920]) {
    for (const theme of ['light', 'dark'] as const) {
      test(`外壳 ${theme} ${width}`, async ({ page }) => {
        const shot = evidencePath(`shell-${theme}-${width}.png`);
        test.skip(shot === null, '未设置 EVIDENCE_DIR');
        await page.emulateMedia({ colorScheme: theme });
        await page.setViewportSize({ width, height: width === 1280 ? 800 : 1080 });
        await openAs(page, 'developer', `/projects/${fixtureProject()}/sessions`);
        await expect(page.locator('html')).toHaveAttribute('data-theme', theme);
        await page.evaluate(() => document.fonts.ready);
        await page.screenshot({ path: shot! });
      });
    }
  }
});
