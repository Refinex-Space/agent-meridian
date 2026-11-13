import type { StorybookConfig } from '@storybook/react-vite';

const config: StorybookConfig = {
  stories: ['../src/**/*.stories.@(ts|tsx)'],
  // pseudo-states：悬停、聚焦等伪类状态各成一个故事（parameters.pseudo），截图与 a11y 检查不依赖真实鼠标。
  addons: [
    '@storybook/addon-a11y',
    '@storybook/addon-docs',
    'msw-storybook-addon',
    'storybook-addon-pseudo-states',
  ],
  framework: '@storybook/react-vite',
  // MSW 的 Service Worker（public/mockServiceWorker.js）。
  staticDirs: ['../public'],
  core: { disableTelemetry: true },
};

export default config;
