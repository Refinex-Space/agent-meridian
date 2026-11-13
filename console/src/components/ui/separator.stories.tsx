import type { Meta, StoryObj } from '@storybook/react-vite';

import { storyFrame } from '@/test/stories';

import { Separator } from './separator';

/** 分隔线：1px 刻线，横竖两个方向；不可交互。 */
const meta = {
  title: '基元/分隔线',
  component: Separator,
  decorators: [storyFrame('w-120')],
} satisfies Meta<typeof Separator>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  name: '默认',
  render: () => (
    <div className="flex flex-col gap-3 text-13">
      <span>模型 qwen-plus</span>
      <Separator />
      <div className="flex h-5 items-center gap-3">
        <span>输入 48.2K</span>
        <Separator orientation="vertical" />
        <span>输出 6.1K</span>
      </div>
    </div>
  ),
};
