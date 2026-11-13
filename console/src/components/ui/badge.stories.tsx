import type { Meta, StoryObj } from '@storybook/react-vite';

import { storyFrame } from '@/test/stories';

import { Badge } from './badge';

/** 徽标：计数、版本号、档位这类短标签，不可交互（没有悬停、聚焦、禁用状态）。状态用 StatusBadge，不用徽标的底色。 */
const meta = {
  title: '基元/徽标',
  component: Badge,
  decorators: [storyFrame()],
} satisfies Meta<typeof Badge>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  name: '默认',
  render: () => (
    <div className="flex items-center gap-3">
      <Badge>v12</Badge>
      <Badge>sandbox</Badge>
      <Badge tone="accent">默认环境</Badge>
      <Badge tone="brass">3</Badge>
      <Badge tone="danger">已停用</Badge>
    </div>
  ),
};

export const LongContent: Story = {
  name: '长内容',
  decorators: [storyFrame('w-48')],
  render: () => (
    <Badge className="max-w-full">
      <span className="truncate">华东大区经营分析生产环境只读副本</span>
    </Badge>
  ),
};
