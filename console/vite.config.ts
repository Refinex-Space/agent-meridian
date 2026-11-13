import { fileURLToPath } from 'node:url';

import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import svgr from 'vite-plugin-svgr';
import { defineConfig } from 'vitest/config';

const src = fileURLToPath(new URL('./src', import.meta.url));
const mocks = fileURLToPath(new URL('./mocks', import.meta.url));

/**
 * 开发与预览时的接口路由（ADR-0055）：控制台按同源调用公开 API 与管理 API，这里按路径转发到本机的三个服务，与生产入口
 * （Ingress）的路由一致：会话、事件、运行概况、待处理动作、投递记录、文件与模型直通进 runtime，知识库进 knowledge，
 * 其余 `/v1/*` 与 `/admin/v1/*` 进 control-plane（架构设计「容器视图」）。地址可用环境变量覆盖。
 */
const runtime = process.env['MERIDIAN_RUNTIME_URL'] ?? 'http://localhost:18080';
const controlPlane = process.env['MERIDIAN_CONTROL_PLANE_URL'] ?? 'http://localhost:18081';
const knowledge = process.env['MERIDIAN_KNOWLEDGE_URL'] ?? 'http://localhost:18083';
const RUNTIME_PATHS =
  'sessions|session-stats|pending-actions|webhook-deliveries|files|chat/completions|responses|embeddings';
const proxy = {
  '/admin/': { target: controlPlane },
  [`^/v1/(${RUNTIME_PATHS})([/?:]|$)`]: { target: runtime },
  '/v1/knowledge-bases': { target: knowledge },
  '/v1/': { target: controlPlane },
};
/** 身份提供方的控制台客户端只登记了 5173 端口的回调地址（deploy/keycloak/meridian-dev-realm.json）。 */
const PORT = 5173;

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    // 自研图标：`import Agent from './agent.svg?react'`。SVGO 优化后由 SVGR 生成组件，
    // 去掉固定宽高（尺寸由 Icon 封装给出），保留 viewBox。
    svgr({
      include: '**/*.svg?react',
      svgrOptions: {
        plugins: ['@svgr/plugin-svgo', '@svgr/plugin-jsx'],
        dimensions: false,
        svgoConfig: {
          plugins: [
            { name: 'preset-default', params: { overrides: { removeViewBox: false } } },
            'removeDimensions',
          ],
        },
      },
    }),
  ],
  resolve: {
    alias: { '@': src, '@mocks': mocks },
  },
  server: { port: PORT, strictPort: true, proxy },
  preview: { port: PORT, strictPort: true, proxy },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}', 'mocks/**/*.test.ts', 'eslint-rules/**/*.test.js'],
    // 时间的显示按本地时区：测试固定为东八区（与 Playwright 的 timezoneId 一致），结果不随运行机器变化。
    env: { TZ: 'Asia/Shanghai' },
    restoreMocks: true,
  },
});
