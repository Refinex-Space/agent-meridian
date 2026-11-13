import type { Meta, StoryObj } from '@storybook/react-vite';

import { focus, storyFrame } from '@/test/stories';

import { StreamIndicator } from './stream-indicator';

/** SSE 连接状态：已连接、连接中、重连中、已断开；已断开时可手动重连。 */
const meta = {
  title: '领域组件/StreamIndicator',
  component: StreamIndicator,
  decorators: [storyFrame()],
  args: { state: 'open', onReconnect: () => undefined },
} satisfies Meta<typeof StreamIndicator>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: '已连接' };

export const Connecting: Story = { name: '连接中', args: { state: 'connecting' } };

export const Reconnecting: Story = { name: '重连中', args: { state: 'reconnecting' } };

export const Closed: Story = { name: '已断开', args: { state: 'closed' } };

export const Focus: Story = {
  name: '聚焦',
  args: { state: 'closed' },
  parameters: focus('[data-slot=button]'),
};
