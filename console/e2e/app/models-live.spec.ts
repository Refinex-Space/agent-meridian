import { expect, test } from '@playwright/test';

import { adminClient, projectPath, unique } from './seed';
import { fixtureProject, openAs } from './support';

const KEY = process.env['DASHSCOPE_API_KEY'];
const MODEL = process.env['MERIDIAN_LIVE_CHAT_MODEL'] ?? 'qwen-plus';
const ENDPOINT = 'https://dashscope.aliyuncs.com/compatible-mode/v1';

// 用例要在页面里输入真实的 API Key：不录跟踪、不截图、不录像，失败时也不把它写进 test-results。
test.use({ trace: 'off', screenshot: 'off', video: 'off' });

test.describe('模型（真实供应商）', () => {
  test.skip(!KEY, '没有设置 DASHSCOPE_API_KEY');

  test(
    '接入 DashScope：凭据录入后连通性测试成功，模型测试显示延迟与用量（T23-A03）',
    { tag: '@live' },
    async ({ page }) => {
      const secretName = unique('e2e-dashscope');
      const accountName = unique('e2e-dashscope');
      const modelName = unique('e2e-qwen');
      await openAs(page, 'admin', `/projects/${fixtureProject()}/models`);

      // 新建供应商账号，凭据在账号表单里就地写入（只能是租户级凭据）。
      await page.getByRole('button', { name: '新建供应商账号' }).first().click();
      const dialog = page.getByRole('dialog', { name: '新建供应商账号' });
      await dialog.getByRole('textbox', { name: '名称' }).fill(accountName);
      await dialog.getByRole('textbox', { name: '端点' }).fill(ENDPOINT);
      await dialog.getByRole('button', { name: '写入凭据' }).click();
      const secret = page.getByRole('dialog', { name: '写入凭据' });
      await secret.getByRole('textbox', { name: '名称' }).fill(secretName);
      await expect(secret.getByRole('radio', { name: '租户内通用' })).toBeChecked();
      await expect(secret.getByRole('radio', { name: '租户内通用' })).toBeDisabled();
      await secret.getByLabel('凭据值', { exact: true }).fill(KEY!);
      await secret.getByRole('button', { name: '保存凭据' }).click();
      await expect(secret).toBeHidden();
      await expect(dialog.getByRole('button', { name: '凭据', exact: true })).toContainText(secretName);
      await dialog.getByRole('button', { name: '创建账号' }).click();
      await expect(dialog).toBeHidden();

      // 连通性测试：列一次模型清单。
      const row = page.getByRole('row').filter({ hasText: accountName });
      await row.getByRole('button', { name: '测试连通性' }).click();
      await expect(row).toContainText('连通', { timeout: 20_000 });
      await expect(row).toContainText(/毫秒|秒/);

      // 添加模型并测试：真实调用一次，显示延迟、回复与用量。
      await page.getByRole('tab', { name: '模型目录' }).click();
      await page.getByRole('button', { name: '添加模型' }).first().click();
      const sheet = page.getByRole('dialog', { name: '添加模型' });
      await sheet.getByRole('button', { name: '供应商账号', exact: true }).click();
      await page.getByRole('option', { name: new RegExp(accountName) }).click();
      await sheet.getByRole('textbox', { name: '名称' }).fill(modelName);
      await sheet.getByRole('textbox', { name: '上游模型名' }).fill(MODEL);
      await sheet.getByRole('textbox', { name: '上下文窗口' }).fill('131072');
      await sheet.getByRole('textbox', { name: '默认最大输出' }).fill('2048');
      await sheet.getByRole('button', { name: '添加模型' }).click();
      await expect(sheet).toBeHidden();
      await page.getByRole('row').filter({ hasText: modelName }).getByRole('button', { name: '测试' }).click();
      const test = page.getByRole('dialog', { name: `测试 ${modelName}` });
      await test.getByRole('button', { name: '发送测试请求' }).click();
      await expect(test).toContainText('调用成功', { timeout: 60_000 });
      await expect(test).toContainText(/延迟\s*\d/);
      const usage = await test.getByText(/输入 \d+、输出 \d+ token/).textContent();
      const [, input, output] = /输入 (\d+)、输出 (\d+) token/.exec(usage ?? '')!;
      expect(Number(input)).toBeGreaterThan(0);
      expect(Number(output)).toBeGreaterThan(0);
      await test.getByRole('button', { name: '完成' }).click();

      // 清理：真实的 API Key 不留在开发库里。
      const api = await adminClient();
      const tenant = { tenant_id: projectPath().tenant_id };
      const models = await api.GET('/admin/v1/tenants/{tenant_id}/models', { params: { path: tenant, query: { limit: 100 } } });
      const accounts = await api.GET('/admin/v1/tenants/{tenant_id}/provider-accounts', {
        params: { path: tenant, query: { limit: 100 } },
      });
      const model = models.data!.data.find((m) => m.name === modelName)!;
      const account = accounts.data!.data.find((a) => a.name === accountName)!;
      await api.DELETE('/admin/v1/tenants/{tenant_id}/models/{model_id}', {
        params: { path: { ...tenant, model_id: model.id } },
      });
      await api.DELETE('/admin/v1/tenants/{tenant_id}/provider-accounts/{provider_account_id}', {
        params: { path: { ...tenant, provider_account_id: account.id } },
      });
      expect(
        (
          await api.DELETE('/admin/v1/tenants/{tenant_id}/secrets/{secret_id}', {
            params: { path: { ...tenant, secret_id: account.secret_id! } },
          })
        ).response.status,
      ).toBe(204);
    },
  );
});
