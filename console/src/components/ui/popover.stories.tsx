import type { Meta, StoryObj } from '@storybook/react-vite';

import { storyFrame } from '@/test/stories';

import { Button } from './button';
import { Checkbox } from './checkbox';
import { Label } from './label';
import { Popover, PopoverContent, PopoverTrigger } from './popover';

/** 弹出层：点击触发、可交互的小面板。 */
const meta = {
  title: '基元/弹出层',
  component: Popover,
  decorators: [storyFrame()],
} satisfies Meta<typeof Popover>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  name: '默认',
  render: () => (
    <Popover defaultOpen>
      <PopoverTrigger asChild>
        <Button>状态</Button>
      </PopoverTrigger>
      <PopoverContent className="flex w-56 flex-col gap-2">
        {['运行中', '等待处理', '空闲', '已终止'].map((label, i) => (
          <div key={label} className="flex items-center gap-2">
            <Checkbox id={`po-${i}`} defaultChecked={i < 2} />
            <Label htmlFor={`po-${i}`}>{label}</Label>
          </div>
        ))}
      </PopoverContent>
    </Popover>
  ),
};

export const LongContent: Story = {
  name: '长内容',
  render: () => (
    <Popover defaultOpen>
      <PopoverTrigger asChild>
        <Button>权限结论</Button>
      </PopoverTrigger>
      <PopoverContent>
        <p className="leading-body">
          bash
          的复合命令按分段逐一判定：任一段命中拒绝规则即拒绝，任一段命中询问规则即询问；只有全部分段都命中放行规则才放行。
        </p>
      </PopoverContent>
    </Popover>
  ),
};
