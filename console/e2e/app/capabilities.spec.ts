import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

import { expect, test } from '@playwright/test';

import { adminClient, baseSpec, fixtureIds, projectPath, seedAgent, unique } from './seed';
import { fixtureProject, openAs } from './support';

/** 在临时目录写一个技能文件夹：SKILL.md、一个脚本与一个隐藏文件。 */
function skillFolder(name: string, description: string): string {
  const root = path.join(mkdtempSync(path.join(tmpdir(), 'meridian-skill-')), name);
  mkdirSync(path.join(root, 'scripts'), { recursive: true });
  writeFileSync(
    path.join(root, 'SKILL.md'),
    `---\nname: ${name}\ndescription: ${description}\nlicense: Apache-2.0\n---\n\n# 周报整理\n\n先汇总本周的销售额与毛利，再列出下滑的品类。\n`,
  );
  writeFileSync(path.join(root, 'scripts', 'summarize.py'), 'import sys\nprint(sys.argv)\n');
  writeFileSync(path.join(root, '.DS_Store'), Buffer.from([0, 1, 2, 3]));
  return root;
}

test.describe('能力', () => {
  test('上传技能包：浏览器端预览、上传新版本，发布后能被智能体引用（T23-A01）', async ({ page }) => {
    const project = fixtureProject();
    const name = unique('e2e-skill');
    await openAs(page, 'developer', `/projects/${project}/capabilities`);
    await expect(page.getByRole('tab', { name: '技能', selected: true })).toBeVisible();

    // 缺 frontmatter 的包：预览直接指出问题，不能上传。
    const broken = path.join(mkdtempSync(path.join(tmpdir(), 'meridian-skill-')), 'broken');
    mkdirSync(broken);
    writeFileSync(path.join(broken, 'SKILL.md'), '# 没有 frontmatter\n');
    await page.getByRole('button', { name: '上传技能' }).first().click();
    const dialog = page.getByRole('dialog', { name: '上传技能' });
    await dialog.getByLabel('选择技能文件夹').setInputFiles(broken);
    await expect(dialog.getByRole('alert')).toContainText('SKILL.md 必须以 --- 包围的 YAML frontmatter 开头');
    await expect(dialog.getByRole('button', { name: '上传技能' })).toBeDisabled();

    // 合法的包：frontmatter、正文与文件清单；隐藏文件被忽略。
    await dialog.getByLabel('选择技能文件夹').setInputFiles(skillFolder(name, '按经营分析的口径整理周报'));
    await expect(dialog.getByRole('alert')).toHaveCount(0);
    await expect(dialog).toContainText(name);
    await expect(dialog).toContainText('按经营分析的口径整理周报');
    await expect(dialog).toContainText('Apache-2.0');
    await expect(dialog.locator('[data-slot="code-block"]')).toContainText('先汇总本周的销售额与毛利');
    await expect(dialog.getByRole('table')).toContainText('SKILL.md');
    await expect(dialog.getByRole('table')).toContainText('scripts/summarize.py');
    await expect(dialog).toContainText('已忽略 1 个隐藏文件');
    await dialog.getByRole('button', { name: '上传技能' }).click();

    await expect(page).toHaveURL(/\/capabilities\/skills\/skl_[^/]+$/);
    const skillId = /skills\/(skl_[^/?]+)/.exec(page.url())![1]!;
    await expect(page.getByRole('heading', { name, level: 1 })).toBeVisible();
    await expect(page.getByText('还没有已发布的智能体版本引用它。')).toBeVisible();

    // 新版本：name 必须一致；版本表多出 v2。
    await page.getByRole('button', { name: '上传新版本' }).click();
    const v2 = page.getByRole('dialog', { name: `上传 ${name} 的 v2` });
    await v2
      .getByLabel('选择技能文件夹')
      .setInputFiles(skillFolder(name, '按经营分析的口径整理周报，附带毛利率'));
    await v2.getByRole('button', { name: '上传 v2' }).click();
    await expect(v2).toBeHidden();
    await expect(page.getByText('最新 v2')).toBeVisible();
    await expect(page.getByRole('table', { name: '技能版本' })).toContainText('附带毛利率');
    await expect(page.getByRole('heading', { name: 'v2 的文件' })).toBeVisible();

    // 智能体引用并发布 v2，技能详情与列表都显示引用方。
    const ids = await fixtureIds();
    const agentName = unique('e2e-skill-user');
    await seedAgent(agentName, [
      { spec: { ...baseSpec(ids.chat), skills: [{ skill_id: skillId, version: 2 }] }, publish: true },
    ]);
    await page.reload();
    await expect(page.getByRole('list', { name: '引用这个技能的智能体版本' })).toContainText(
      `${agentName} v1`,
    );
    await page.goto(`/projects/${project}/capabilities`);
    await expect(page.getByRole('row').filter({ hasText: name })).toContainText(`${agentName} v1`);
  });

  test('登记 MCP 服务：就地写入凭据，工具清单出现，逐个标注副作用等级并保存（T23-A02）', async ({ page }) => {
    const project = fixtureProject();
    const api = await adminClient();
    // knowledge 自带的 MCP 服务（/mcp/knowledge）按 API Key 认证：建一个只读知识库的 Key，作为请求头凭据。
    const key = await api.POST('/admin/v1/tenants/{tenant_id}/projects/{project_id}/api-keys', {
      params: { path: projectPath() },
      body: { name: unique('e2e-mcp-key'), scopes: ['knowledge:read'] },
    });
    expect(key.response.status).toBe(201);
    const serverName = unique('e2e_kb').replaceAll('-', '_');

    await openAs(page, 'admin', `/projects/${project}/capabilities?tab=mcp-servers`);
    await expect(page.getByRole('tab', { name: 'MCP 服务', selected: true })).toBeVisible();
    await page.getByRole('button', { name: '登记 MCP 服务' }).first().click();
    const dialog = page.getByRole('dialog', { name: '登记 MCP 服务' });
    await dialog.getByRole('textbox', { name: '服务名' }).fill(serverName);
    await dialog.getByRole('textbox', { name: '地址' }).fill('http://knowledge:18083/mcp/knowledge');

    // 请求头 Authorization（添加第一个请求头时预填）的凭据就地写入（只给本项目），写好后自动选中。
    await dialog.getByRole('button', { name: '添加请求头' }).click();
    await expect(dialog.getByRole('textbox', { name: '第 1 个请求头的名称' })).toHaveValue('Authorization');
    await dialog.getByRole('button', { name: '写入凭据' }).click();
    const secret = page.getByRole('dialog', { name: '写入凭据' });
    const secretName = unique('e2e-mcp-token');
    await secret.getByRole('textbox', { name: '名称' }).fill(secretName);
    await expect(secret.getByRole('radio', { name: /^只给/ })).toBeChecked();
    await secret.getByLabel('凭据值', { exact: true }).fill(`Bearer ${key.data!.key}`);
    await secret.getByRole('button', { name: '保存凭据' }).click();
    await expect(secret).toBeHidden();
    await expect(dialog.getByRole('button', { name: 'Authorization的凭据' })).toContainText(secretName);

    await dialog.getByRole('button', { name: '连接并登记' }).click();
    await expect(page).toHaveURL(/\/capabilities\/mcp-servers\/mcp_[^/]+$/);
    const serverId = /mcp-servers\/(mcp_[^/?]+)/.exec(page.url())![1]!;
    await expect(page.getByRole('heading', { name: serverName, level: 1 })).toBeVisible();
    const tools = page.getByRole('table', { name: '工具清单' });
    await expect(tools).toContainText('knowledge_search');

    // 副作用等级：说明在 info 提示里；改一个工具后出现保存栏，保存后从服务端重读仍是新值。
    await page.getByRole('button', { name: '副作用等级的说明' }).hover();
    await expect(page.getByRole('tooltip')).toContainText('恢复时不重放');
    await tools.getByRole('combobox', { name: 'knowledge_search 的副作用等级' }).click();
    await page.getByRole('option', { name: '无副作用' }).click();
    await expect(page.getByRole('status').filter({ hasText: '有 1 个工具的标注未保存' })).toBeVisible();
    await page.getByRole('button', { name: '保存标注' }).click();
    await expect(page.getByText('有 1 个工具的标注未保存')).toHaveCount(0);
    await page.reload();
    await expect(
      page
        .getByRole('table', { name: '工具清单' })
        .getByRole('combobox', { name: 'knowledge_search 的副作用等级' }),
    ).toHaveText('无副作用');
    const saved = await api.GET(
      '/admin/v1/tenants/{tenant_id}/projects/{project_id}/mcp-servers/{mcp_server_id}',
      {
        params: { path: { ...projectPath(), mcp_server_id: serverId } },
      },
    );
    expect(saved.data?.tool_side_effects).toEqual({ knowledge_search: 'none' });
    expect(saved.data?.last_check?.status).toBe('ok');

    // 重新探测：连接状态与探测时间就地更新。
    await page.getByRole('button', { name: '重新探测' }).click();
    await expect(page.getByText('连通').first()).toBeVisible();
  });

  test('自定义工具的说明：声明与回传的请求', async ({ page }) => {
    await openAs(page, 'viewer', `/projects/${fixtureProject()}/capabilities?tab=custom-tools`);
    await expect(page.getByRole('heading', { name: '创建会话时声明工具' })).toBeVisible();
    const blocks = page.locator('[data-slot="code-block"]');
    await expect(blocks.first()).toContainText('"custom_tools"');
    await expect(blocks.nth(1)).toContainText('"user.custom_tool_result"');
    await expect(page.getByRole('button', { name: '复制创建会话并声明自定义工具的请求体' })).toBeVisible();
    // 观察者只能看：技能页的上传按钮禁用并说明原因。
    await page.getByRole('tab', { name: '技能' }).click();
    await expect(page.getByRole('button', { name: '上传技能' }).first()).toHaveAttribute(
      'aria-disabled',
      'true',
    );
  });
});
