import type { Meta, StoryObj } from '@storybook/react-vite';

import { focus, hover, storyFrame } from '@/test/stories';

import { Button } from './button';
import { PlusIcon, TrashIcon, XIcon } from './glyphs';
import { Icon } from './icon';

function Variants({ disabled = false, pending = false }: { disabled?: boolean; pending?: boolean }) {
  return (
    <div className="flex flex-wrap items-center gap-3">
      <Button variant="primary" disabled={disabled} pending={pending} icon={<Icon glyph={PlusIcon} />}>
        创建智能体
      </Button>
      <Button disabled={disabled} pending={pending}>
        编辑
      </Button>
      <Button variant="ghost" disabled={disabled} pending={pending}>
        取消
      </Button>
      <Button variant="danger" disabled={disabled} pending={pending} icon={<Icon glyph={TrashIcon} />}>
        删除智能体
      </Button>
      <Button variant="ghost" size="icon-md" aria-label="关闭" disabled={disabled}>
        <Icon glyph={XIcon} />
      </Button>
    </div>
  );
}

/** 按钮：主操作 primary（一页通常一个）、次要 secondary、文字 ghost、危险 danger；尺寸 sm 28px 与 md 32px。 */
const meta = {
  title: '基元/按钮',
  component: Button,
  decorators: [storyFrame()],
} satisfies Meta<typeof Button>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: '默认', render: () => <Variants /> };

export const Hover: Story = {
  name: '悬停',
  parameters: hover('[data-slot=button]'),
  render: () => <Variants />,
};

export const Focus: Story = {
  name: '聚焦',
  parameters: focus('[data-slot=button]'),
  render: () => <Variants />,
};

export const Disabled: Story = { name: '禁用', render: () => <Variants disabled /> };

export const Loading: Story = { name: '加载', render: () => <Variants pending /> };

export const Sizes: Story = {
  name: '尺寸',
  render: () => (
    <div className="flex items-center gap-3">
      <Button size="sm">导出</Button>
      <Button size="md">导出</Button>
      <Button size="icon-sm" variant="ghost" aria-label="关闭">
        <Icon glyph={XIcon} />
      </Button>
    </div>
  ),
};

export const LongContent: Story = {
  name: '长内容',
  decorators: [storyFrame('w-72')],
  render: () => (
    <Button variant="primary" className="max-w-full">
      <span className="truncate">发布版本并通知所有订阅了这个智能体的业务系统</span>
    </Button>
  ),
};
