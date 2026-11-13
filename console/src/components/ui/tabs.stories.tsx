import type { Meta, StoryObj } from '@storybook/react-vite';

import { focus, hover, storyFrame } from '@/test/stories';

import { Tabs, TabsContent, TabsList, TabsTrigger } from './tabs';

function Example({ disabled = false, labels = ['概要', '版本', '会话', '权限'] }) {
  return (
    <Tabs defaultValue={labels[0]}>
      <TabsList>
        {labels.map((label, i) => (
          <TabsTrigger key={label} value={label} disabled={disabled && i === labels.length - 1}>
            {label}
          </TabsTrigger>
        ))}
      </TabsList>
      {labels.map((label) => (
        <TabsContent key={label} value={label} className="py-4 text-14">
          {label}的内容
        </TabsContent>
      ))}
    </Tabs>
  );
}

/** 标签页：与内容左边缘对齐的文字标签，当前页墨色加 2px 海图蓝下划线。 */
const meta = {
  title: '基元/标签页',
  component: Tabs,
  decorators: [storyFrame('w-160')],
} satisfies Meta<typeof Tabs>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: '默认', render: () => <Example /> };

export const Hover: Story = {
  name: '悬停',
  parameters: hover('[data-slot=tabs-trigger][data-state=inactive]'),
  render: () => <Example />,
};

export const Focus: Story = {
  name: '聚焦',
  parameters: focus('[data-slot=tabs-trigger][data-state=active]'),
  render: () => <Example />,
};

export const Disabled: Story = { name: '禁用', render: () => <Example disabled /> };

export const LongContent: Story = {
  name: '长内容',
  decorators: [storyFrame('w-120 overflow-x-auto')],
  render: () => <Example labels={['概要', '版本历史', '最近的会话', '权限与工具', '子智能体', '通知地址']} />,
};
