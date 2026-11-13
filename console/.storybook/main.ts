import type { StorybookConfig } from '@storybook/react-vite';

const config: StorybookConfig = {
  stories: ['../src/**/*.stories.@(ts|tsx)'],
  addons: ['@storybook/addon-a11y', '@storybook/addon-docs', 'msw-storybook-addon'],
  framework: '@storybook/react-vite',
  // MSW 的 Service Worker（public/mockServiceWorker.js）。
  staticDirs: ['../public'],
  core: { disableTelemetry: true },
};

export default config;
