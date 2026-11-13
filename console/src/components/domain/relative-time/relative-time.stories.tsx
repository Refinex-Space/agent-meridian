import type { Meta, StoryObj } from '@storybook/react-vite';
import { userEvent, within } from 'storybook/test';

import { storyFrame } from '@/test/stories';

import { RelativeTime } from './relative-time';

const NOW = Date.parse('2026-11-13T10:00:00+08:00');

/** 相对时间：列表中用，悬停显示带时区的绝对时间。 */
const meta = {
  title: '领域组件/RelativeTime',
  component: RelativeTime,
  decorators: [storyFrame(undefined, 'text-13')],
  args: { value: '2026-11-13T09:55:00+08:00', now: NOW },
} satisfies Meta<typeof RelativeTime>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  name: '默认',
  render: (args) => (
    <ul className="flex flex-col gap-2">
      {[
        '2026-11-13T09:59:40+08:00',
        '2026-11-13T09:55:00+08:00',
        '2026-11-13T07:00:00+08:00',
        '2026-11-11T10:00:00+08:00',
        '2026-10-02T16:40:00+08:00',
        '2025-12-30T16:40:00+08:00',
      ].map((value) => (
        <li key={value}>
          <RelativeTime {...args} value={value} />
        </li>
      ))}
    </ul>
  ),
};

export const Hover: Story = {
  name: '悬停',
  decorators: [storyFrame(undefined, 'pt-16 text-13')],
  play: async ({ canvasElement }) => {
    await userEvent.hover(within(canvasElement).getByText('5 分钟前'));
  },
};

export const Future: Story = { name: '将来', args: { value: '2026-11-13T10:20:00+08:00' } };
