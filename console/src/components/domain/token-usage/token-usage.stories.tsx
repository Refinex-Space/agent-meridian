import type { Meta, StoryObj } from '@storybook/react-vite';
import { userEvent, within } from 'storybook/test';

import { storyFrame } from '@/test/stories';

import { TokenUsage } from './token-usage';

const USAGE = {
  input_tokens: 48_210,
  output_tokens: 6_104,
  cached_tokens: 31_000,
  cache_creation_tokens: 0,
  reasoning_tokens: 0,
};

/** token 用量与成本：行内一行带单位的数字，详情逐项精确值；悬停缩写看精确值。 */
const meta = {
  title: '领域组件/TokenUsage',
  component: TokenUsage,
  decorators: [storyFrame()],
  args: { usage: USAGE },
} satisfies Meta<typeof TokenUsage>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: '默认' };

export const WithCost: Story = {
  name: '带成本',
  args: { usage: { ...USAGE, reasoning_tokens: 2_380 }, cost: { amount: 1.237, currency: 'CNY' } },
};

export const Hover: Story = {
  name: '悬停',
  decorators: [storyFrame(undefined, 'pt-16')],
  play: async ({ canvasElement }) => {
    await userEvent.hover(within(canvasElement).getByText('48K'));
  },
};

export const Detail: Story = {
  name: '详情',
  decorators: [storyFrame('w-72')],
  args: {
    variant: 'detail',
    usage: { ...USAGE, cache_creation_tokens: 1_024, reasoning_tokens: 2_380 },
    cost: { amount: 0.0004, currency: 'CNY' },
  },
};

export const Empty: Story = {
  name: '空',
  args: {
    usage: {
      input_tokens: 0,
      output_tokens: 0,
      cached_tokens: 0,
      cache_creation_tokens: 0,
      reasoning_tokens: 0,
    },
  },
};

export const LongContent: Story = {
  name: '长内容',
  args: {
    usage: {
      input_tokens: 1_284_330_112,
      output_tokens: 98_204_551,
      cached_tokens: 902_118_004,
      cache_creation_tokens: 12_004_998,
      reasoning_tokens: 40_118_220,
    },
    cost: { amount: 128_403.5, currency: 'CNY' },
  },
};
