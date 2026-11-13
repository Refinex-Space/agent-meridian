import type { Meta, StoryObj } from '@storybook/react-vite';
import { userEvent, within } from 'storybook/test';

import { focus, hover, storyFrame } from '@/test/stories';

import { CopyableId } from './copyable-id';

/** 带前缀 ID：等宽、中间省略、一键复制；完整 ID 在提示里。 */
const meta = {
  title: '领域组件/CopyableId',
  component: CopyableId,
  decorators: [storyFrame()],
  args: { id: 'ses_01JC8Z4V7QX3M9N2P8R5', label: '会话 ID' },
} satisfies Meta<typeof CopyableId>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: '默认' };

export const Hover: Story = { name: '悬停', parameters: hover('[data-slot=copyable-id]') };

export const Focus: Story = { name: '聚焦', parameters: focus('[data-slot=copyable-id]') };

export const Copied: Story = {
  name: '已复制',
  play: async ({ canvasElement }) => {
    await userEvent.click(within(canvasElement).getByRole('button'));
  },
};

export const Full: Story = {
  name: '完整显示',
  args: { id: 'req_01JC9A7M2KX4Q8', label: '请求 ID', full: true },
};

export const LongContent: Story = {
  name: '长内容',
  decorators: [storyFrame('w-48')],
  args: { id: 'evt_01JC8Z4V7QX3M9N2P8R5T6W0YA', label: '事件 ID', full: true },
};
