import type { Meta, StoryObj } from '@storybook/react-vite';

import { focus, hover, storyFrame } from '@/test/stories';

import { Label } from './label';
import { Switch } from './switch';

function Options({ disabled = false }: { disabled?: boolean }) {
  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-3">
        <Switch id="sw-on" defaultChecked disabled={disabled} />
        <Label htmlFor="sw-on">启用通知地址</Label>
      </div>
      <div className="flex items-center gap-3">
        <Switch id="sw-off" disabled={disabled} />
        <Label htmlFor="sw-off">暴露思考内容</Label>
      </div>
    </div>
  );
}

/** 开关：立即生效的二元设置；4px 圆角的拨动开关，不做胶囊。 */
const meta = {
  title: '基元/开关',
  component: Switch,
  decorators: [storyFrame('w-80')],
} satisfies Meta<typeof Switch>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: '默认', render: () => <Options /> };

export const Hover: Story = {
  name: '悬停',
  parameters: hover('[data-slot=switch]'),
  render: () => <Options />,
};

export const Focus: Story = {
  name: '聚焦',
  parameters: focus('#sw-on'),
  render: () => <Options />,
};

export const Disabled: Story = { name: '禁用', render: () => <Options disabled /> };

export const LongContent: Story = {
  name: '长内容',
  render: () => (
    <div className="flex items-start gap-3">
      <span className="flex h-5 items-center">
        <Switch id="sw-long" defaultChecked />
      </span>
      <Label htmlFor="sw-long" className="items-start">
        会话结束后保留工作区文件 30 天，到期由清理任务删除并写入审计日志
      </Label>
    </div>
  ),
};
