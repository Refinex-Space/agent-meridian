import type { Meta, StoryObj } from '@storybook/react-vite';

import { storyFrame } from '@/test/stories';

import { Checkbox } from './checkbox';
import { Label } from './label';

/** 标签：每个控件都有可访问名称；对应控件禁用时文字变灰。 */
const meta = {
  title: '基元/标签',
  component: Label,
  decorators: [storyFrame('w-80')],
} satisfies Meta<typeof Label>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  name: '默认',
  render: () => (
    <div className="flex items-center gap-2">
      <Checkbox id="label-default" />
      <Label htmlFor="label-default">允许智能体读取工作区文件</Label>
    </div>
  ),
};

export const Disabled: Story = {
  name: '禁用',
  render: () => (
    <div className="flex items-center gap-2">
      <Checkbox id="label-disabled" disabled />
      <Label htmlFor="label-disabled">允许智能体读取工作区文件</Label>
    </div>
  ),
};

export const LongContent: Story = {
  name: '长内容',
  render: () => (
    <div className="flex items-start gap-2">
      <span className="flex h-5 items-center">
        <Checkbox id="label-long" />
      </span>
      <Label htmlFor="label-long" className="items-start">
        允许智能体在 sandbox 档的会话沙箱中执行 bash 命令，命令仍按工具权限规则逐条判定
      </Label>
    </div>
  ),
};
