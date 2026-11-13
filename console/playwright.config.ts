import { defineConfig, devices } from '@playwright/test';

const PORT = 6007;

/**
 * 端到端测试跑在构建后的 Storybook 上（生产模式的 React，接口由 MSW 模拟）：时间轴性能、动效、无障碍与评审截图。
 * T21 之后的页面用例改为连接运行中的控制台与脚本化模型服务（testing.md「前端测试」）。
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  workers: 1,
  reporter: [['list']],
  use: {
    ...devices['Desktop Chrome'],
    baseURL: `http://127.0.0.1:${PORT}`,
    viewport: { width: 1280, height: 800 },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
  },
  webServer: {
    command: `pnpm build-storybook && pnpm exec vite preview --outDir storybook-static --host 127.0.0.1 --port ${PORT} --strictPort`,
    url: `http://127.0.0.1:${PORT}/iframe.html`,
    reuseExistingServer: !process.env['CI'],
    timeout: 300_000,
  },
});
