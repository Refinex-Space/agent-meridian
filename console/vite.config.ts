import { fileURLToPath } from 'node:url';

import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import svgr from 'vite-plugin-svgr';
import { defineConfig } from 'vitest/config';

const src = fileURLToPath(new URL('./src', import.meta.url));
const mocks = fileURLToPath(new URL('./mocks', import.meta.url));

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
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}', 'mocks/**/*.test.ts', 'eslint-rules/**/*.test.js'],
    restoreMocks: true,
  },
});
