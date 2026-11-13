import { mkdirSync } from 'node:fs';
import path from 'node:path';

import { expect, type Page } from '@playwright/test';

/** 本机后端（scripts/dev/console-e2e-env.sh）。 */
export const CONTROL_PLANE = 'http://localhost:18081';
export const KEYCLOAK = 'http://localhost:18082';
const REALM = `${KEYCLOAK}/realms/meridian-dev`;

/** 开发 realm 的用户（deploy/keycloak/meridian-dev-realm.json），口令都是 meridian-dev。 */
export const USERS = {
  admin: {
    username: 'dev-admin',
    subject: '00000000-0000-4000-8000-000000000001',
    email: 'dev-admin@meridian.local',
    name: 'Dev Admin',
  },
  developer: {
    username: 'dev-developer',
    subject: '00000000-0000-4000-8000-000000000002',
    email: 'dev-developer@meridian.local',
    name: 'Dev Developer',
  },
  viewer: {
    username: 'dev-viewer',
    subject: '00000000-0000-4000-8000-000000000003',
    email: 'dev-viewer@meridian.local',
    name: 'Dev Viewer',
  },
} as const;

const PASSWORD = 'meridian-dev';

/** 全局准备建的租户与项目（按名称查找，可重复执行）。 */
export const FIXTURE = {
  tenant: 'T21 控制台验收',
  projects: ['华东经营分析', '门店运营'],
} as const;

/** 全局准备写入的项目 ID。 */
export function fixtureProject(which: 'main' | 'other' = 'main'): string {
  const id = process.env[which === 'main' ? 'E2E_PROJECT_ID' : 'E2E_OTHER_PROJECT_ID'];
  if (!id) throw new Error('缺少 E2E_PROJECT_ID：全局准备没有执行');
  return id;
}

/** 开发脚本与测试用的客户端（可直接授权，不经浏览器）取 access token，只用于全局准备。 */
export async function passwordToken(username: string): Promise<string> {
  const response = await fetch(`${REALM}/protocol/openid-connect/token`, {
    method: 'POST',
    body: new URLSearchParams({
      grant_type: 'password',
      client_id: 'meridian-dev-cli',
      username,
      password: PASSWORD,
      scope: 'openid',
    }),
  });
  if (!response.ok) {
    throw new Error(
      `从 Keycloak 取 ${username} 的令牌失败：HTTP ${response.status}（先运行 scripts/dev/console-e2e-env.sh up）`,
    );
  }
  const body = (await response.json()) as { access_token: string };
  return body.access_token;
}

/** 当前页面是身份提供方的登录页。 */
export async function expectLoginPage(page: Page): Promise<void> {
  await expect(page).toHaveURL(new RegExp(`^${REALM}/protocol/openid-connect/auth`));
  await expect(page.locator('#username')).toBeVisible();
}

/** 在 Keycloak 的登录页上输入用户名与口令。 */
export async function signIn(page: Page, user: keyof typeof USERS): Promise<void> {
  await expectLoginPage(page);
  await page.locator('#username').fill(USERS[user].username);
  await page.locator('#password').fill(PASSWORD);
  await page.locator('#kc-login').click();
}

/** 打开控制台的一个页面并登录，等到外壳出现。 */
export async function openAs(page: Page, user: keyof typeof USERS, url: string): Promise<void> {
  await page.goto(url);
  await signIn(page, user);
  await expect(page.getByRole('navigation', { name: '主导航' })).toBeVisible();
}

/** 评审截图目录：设置了 EVIDENCE_DIR 时写入。 */
export function evidencePath(name: string): string | null {
  const dir = process.env['EVIDENCE_DIR'];
  if (!dir) return null;
  const resolved = path.resolve(dir);
  mkdirSync(resolved, { recursive: true });
  return path.join(resolved, name);
}
