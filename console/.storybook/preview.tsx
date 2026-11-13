import type { Decorator, Preview } from '@storybook/react-vite';
import { mswLoader } from 'msw-storybook-addon/csf3';

import { TooltipProvider } from '../src/components/ui/tooltip';
import '../src/styles/index.css';

type Theme = 'light' | 'dark';

/** 主题：写到 <html data-theme>，令牌按 color-scheme 取亮暗值。 */
const withTheme: Decorator = (Story, context) => {
  const theme = (context.globals['theme'] as Theme | undefined) ?? 'light';
  document.documentElement.dataset['theme'] = theme;
  return (
    <TooltipProvider>
      <Story />
    </TooltipProvider>
  );
};

const preview: Preview = {
  globalTypes: {
    theme: {
      description: '主题',
      toolbar: {
        title: '主题',
        icon: 'mirror',
        items: [
          { value: 'light', title: '亮色' },
          { value: 'dark', title: '暗色' },
        ],
        dynamicTitle: true,
      },
    },
  },
  initialGlobals: { theme: 'light' },
  decorators: [withTheme],
  // MSW：故事在 beforeEach 中用 context.msw.use(...) 登记自己的处理器。
  loaders: [mswLoader()],
  parameters: {
    layout: 'fullscreen',
    a11y: { test: 'error' },
    controls: { expanded: true },
  },
};

export default preview;
