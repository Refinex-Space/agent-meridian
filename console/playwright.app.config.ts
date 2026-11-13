import { defineConfig, devices } from '@playwright/test';

/** 身份提供方的控制台客户端只登记了 5173 端口（deploy/keycloak/meridian-dev-realm.json）；与 Keycloak 同为 localhost。 */
const BASE_URL = 'http://localhost:5173';

/**
 * 控制台本身的端到端用例（T21 起）：构建后的控制台由 `vite preview` 提供，接口按路径转发到本机的后端（vite.config.ts），
 * 登录走本地 Keycloak 的开发 realm，模型调用走脚本化模型服务。先用 `scripts/dev/console-e2e-env.sh up` 启动后端；
 * 全局准备（`e2e/app/global-setup.ts`）建好验收用的租户、项目、成员与预算。跑在 Storybook 上的用例见 playwright.config.ts。
 */
export default defineConfig({
  testDir: './e2e/app',
  timeout: 60_000,
  workers: 1,
  reporter: [['list']],
  globalSetup: './e2e/app/global-setup.ts',
  use: {
    ...devices['Desktop Chrome'],
    baseURL: BASE_URL,
    viewport: { width: 1280, height: 800 },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'pnpm build && pnpm exec vite preview',
    url: `${BASE_URL}/config.json`,
    reuseExistingServer: !process.env['CI'],
    timeout: 300_000,
  },
});
