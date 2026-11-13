import { expect, test, type Page } from '@playwright/test';

import { adminClient, projectPath, unique } from './seed';
import { FIXTURE, fixtureProject, openAs, SCRIPTED_MODEL_URL } from './support';

/** 在模型目录的抽屉里添加一个对话模型。 */
async function addChatModel(
  page: Page,
  opts: { account: string; name: string; upstream: string; priceBook?: string },
): Promise<void> {
  await page.getByRole('tab', { name: '模型目录' }).click();
  await page.getByRole('button', { name: '添加模型' }).first().click();
  const sheet = page.getByRole('dialog', { name: '添加模型' });
  await sheet.getByRole('button', { name: '供应商账号', exact: true }).click();
  await page.getByRole('option', { name: new RegExp(opts.account) }).click();
  await sheet.getByRole('textbox', { name: '名称' }).fill(opts.name);
  await sheet.getByRole('textbox', { name: '上游模型名' }).fill(opts.upstream);
  await sheet.getByRole('textbox', { name: '上下文窗口' }).fill('131072');
  await sheet.getByRole('textbox', { name: '默认最大输出' }).fill('2048');
  if (opts.priceBook) {
    await sheet.getByRole('button', { name: '价格表', exact: true }).click();
    await page.getByRole('option', { name: new RegExp(opts.priceBook) }).click();
  }
  await sheet.getByRole('button', { name: '添加模型' }).click();
  await expect(sheet).toBeHidden();
  await expect(page.getByRole('row').filter({ hasText: opts.name })).toBeVisible();
}

/** 在模型目录里测试一个模型，返回结果对话框。 */
async function testModel(page: Page, name: string) {
  await page.getByRole('row').filter({ hasText: name }).getByRole('button', { name: '测试' }).click();
  const dialog = page.getByRole('dialog', { name: `测试 ${name}` });
  await expect(dialog).toContainText('会产生少量费用');
  await dialog.getByRole('button', { name: /发送测试请求|再测一次/ }).click();
  await expect(dialog).toContainText('调用成功', { timeout: 30_000 });
  await expect(dialog).toContainText('延迟');
  await expect(dialog).toContainText(/输入 \d+、输出 \d+ token/);
  return dialog;
}

/** 等账本里出现这个模型的第 n 条用量（outbox → Stream → control-plane 入账是异步的）。 */
async function usageOf(modelId: string, count: number) {
  const api = await adminClient();
  let records: {
    cost: string | null;
    price_book_version: number | null;
    input_tokens: number;
    output_tokens: number;
  }[] = [];
  await expect
    .poll(
      async () => {
        const page = await api.GET('/admin/v1/tenants/{tenant_id}/projects/{project_id}/usage-records', {
          params: { path: projectPath(), query: { model_id: modelId, limit: 100 } },
        });
        records = page.data?.data ?? [];
        return records.length;
      },
      { timeout: 30_000 },
    )
    .toBeGreaterThanOrEqual(count);
  // 账本按时间从新到旧；按时间正序给调用方。
  return [...records].reverse();
}

test.describe('模型', () => {
  test('供应商账号：新建后测试连通性，结果就地出现在这一行', async ({ page }) => {
    const name = unique('e2e-provider');
    await openAs(page, 'admin', `/projects/${fixtureProject()}/models`);
    await expect(page.getByRole('tab', { name: '供应商账号', selected: true })).toBeVisible();
    await page.getByRole('button', { name: '新建供应商账号' }).first().click();
    const dialog = page.getByRole('dialog', { name: '新建供应商账号' });
    await dialog.getByRole('textbox', { name: '名称' }).fill(name);
    await dialog.getByRole('textbox', { name: '端点' }).fill(SCRIPTED_MODEL_URL);
    await dialog.getByRole('button', { name: '创建账号' }).click();
    await expect(dialog).toBeHidden();

    const row = page.getByRole('row').filter({ hasText: name });
    await expect(row).toContainText('未测试');
    await page.getByRole('button', { name: '连通性测试的说明' }).hover();
    await expect(page.getByRole('tooltip')).toContainText('不产生用量');
    await row.getByRole('button', { name: '测试连通性' }).click();
    await expect(row).toContainText('连通');
    await expect(row).toContainText(/\d+ 毫秒|秒/);

    // 账号下没有模型：可以删除。
    await row.getByRole('button', { name: `${name} 的更多操作` }).click();
    await page.getByRole('menuitem', { name: '删除' }).click();
    const confirm = page.getByRole('alertdialog', { name: `删除供应商账号 ${name}` });
    await confirm.getByRole('textbox').fill(name);
    await confirm.getByRole('button', { name: '删除账号' }).click();
    await expect(page.getByRole('row').filter({ hasText: name })).toHaveCount(0);
  });

  test('价格表新版本生效后，历史用量的成本不变（T23-A05）', async ({ page }) => {
    const book = unique('e2e-price');
    const model = unique('e2e-priced');
    await openAs(page, 'admin', `/projects/${fixtureProject()}/models?tab=price-books`);

    // 价格表 v1：输入 1、输出 2（每百万 token）。
    await page.getByRole('button', { name: '新建价格表' }).first().click();
    const create = page.getByRole('dialog', { name: '新建价格表' });
    await create.getByRole('textbox', { name: '名称' }).fill(book);
    await create.getByRole('textbox', { name: '输入（CNY / 百万 token）' }).fill('1');
    await create.getByRole('textbox', { name: '输出（CNY / 百万 token）' }).fill('2');
    await create.getByRole('button', { name: '创建并发布 v1' }).click();
    await expect(create).toBeHidden();
    await expect(page.getByRole('row').filter({ hasText: book })).toContainText('v1');

    // 用它计价的模型（上游是脚本化模型服务），测试一次产生第一条用量。
    await addChatModel(page, {
      account: FIXTURE.providerAccount,
      name: model,
      upstream: 'scripted',
      priceBook: book,
    });
    const api = await adminClient();
    const models = await api.GET('/admin/v1/tenants/{tenant_id}/models', {
      params: { path: { tenant_id: projectPath().tenant_id }, query: { limit: 100 } },
    });
    const modelId = models.data!.data.find((m) => m.name === model)!.id;
    await (await testModel(page, model)).getByRole('button', { name: '完成' }).click();
    const [first] = await usageOf(modelId, 1);
    expect(first!.price_book_version).toBe(1);
    expect(Number(first!.cost)).toBeGreaterThan(0);
    const firstCost = first!.cost;

    // 发布 v2：单价乘以 10，立即生效。
    await page.getByRole('tab', { name: '价格表' }).click();
    await page.getByRole('row').filter({ hasText: book }).click();
    await expect(page.getByRole('heading', { name: book, level: 1 })).toBeVisible();
    await page.getByRole('button', { name: '发布新版本' }).click();
    const publish = page.getByRole('dialog', { name: `发布 ${book} v2` });
    await expect(publish).toContainText('已经结算的用量仍按当时的版本，成本不变');
    await publish.getByRole('textbox', { name: '输入（CNY / 百万 token）' }).fill('10');
    await publish.getByRole('textbox', { name: '输出（CNY / 百万 token）' }).fill('20');
    await publish.getByRole('button', { name: '发布 v2' }).click();
    await expect(publish).toBeHidden();
    await expect(page.getByText('当前 v2')).toBeVisible();
    const versions = page.getByRole('table');
    await expect(versions.getByRole('row').nth(1)).toContainText('v2');
    await expect(versions.getByRole('row').nth(2)).toContainText('v1');
    await expect(page.getByRole('list', { name: '用它计价的模型' })).toContainText(model);

    // 再测一次：新用量按 v2 计价，第一条的成本与版本不变。
    await page.goto(`/projects/${fixtureProject()}/models?tab=catalog`);
    await testModel(page, model);
    const [before, after] = await usageOf(modelId, 2);
    expect(before!.cost).toBe(firstCost);
    expect(before!.price_book_version).toBe(1);
    expect(after!.price_book_version).toBe(2);
    const perToken = (r: typeof before) => Number(r!.cost) / (r!.input_tokens + 2 * r!.output_tokens);
    expect(perToken(after) / perToken(before)).toBeCloseTo(10, 5);
  });

  test('项目观察者只能看：看不到写操作，测试模型按钮说明原因', async ({ page }) => {
    await openAs(page, 'viewer', `/projects/${fixtureProject()}/models?tab=catalog`);
    await expect(page.getByRole('row').filter({ hasText: 'scripted-chat' })).toBeVisible();
    await expect(page.getByRole('button', { name: '添加模型' }).first()).toHaveAttribute(
      'aria-disabled',
      'true',
    );
    await expect(
      page.getByRole('row').filter({ hasText: 'scripted-chat' }).getByRole('button', { name: '测试' }),
    ).toHaveAttribute('aria-disabled', 'true');
    await expect(page.getByRole('button', { name: 'scripted-chat 的更多操作' })).toHaveCount(0);
  });
});
