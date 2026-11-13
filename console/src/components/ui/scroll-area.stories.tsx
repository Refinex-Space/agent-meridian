import type { Meta, StoryObj } from '@storybook/react-vite';

import { storyFrame } from '@/test/stories';

import { ScrollArea } from './scroll-area';

const LINES = Array.from(
  { length: 40 },
  (_, i) => `第 ${i + 1} 轮  调用 knowledge_search  结果 ${(i * 7) % 13} 条`,
);

/** 滚动区域：6px 细滚动条，滑块用控件边界色。 */
const meta = {
  title: '基元/滚动区域',
  component: ScrollArea,
  decorators: [storyFrame('w-80')],
} satisfies Meta<typeof ScrollArea>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  name: '默认',
  render: () => (
    <ScrollArea className="h-24 rounded-panel border border-rule">
      <p className="p-3 text-13">内容没有超出时不显示滚动条。</p>
    </ScrollArea>
  ),
};

export const LongContent: Story = {
  name: '长内容',
  render: () => (
    <ScrollArea className="h-60 rounded-panel border border-rule" type="always">
      <ul className="p-3 text-13 leading-6">
        {LINES.map((line) => (
          <li key={line}>{line}</li>
        ))}
      </ul>
    </ScrollArea>
  ),
};
