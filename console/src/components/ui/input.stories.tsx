import type { Meta, StoryObj } from '@storybook/react-vite';
import type { ComponentProps } from 'react';

import { focus, hover, storyFrame } from '@/test/stories';

import { Input } from './input';
import { Label } from './label';

function Field({ hint, error, ...props }: ComponentProps<typeof Input> & { hint?: string; error?: string }) {
  return (
    <div className="flex flex-col gap-1">
      <Label htmlFor="agent-name">名称</Label>
      <Input
        id="agent-name"
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? 'agent-name-error' : undefined}
        {...props}
      />
      {error && (
        <p id="agent-name-error" className="text-12 text-danger">
          {error}
        </p>
      )}
      {hint && !error && <p className="text-12 text-ink-2">{hint}</p>}
    </div>
  );
}

/** 输入框：md 32px 与按钮同高，sm 28px 用于筛选栏；只读保留墨色文字，禁用文字变灰。 */
const meta = {
  title: '基元/输入框',
  component: Input,
  decorators: [storyFrame('w-80')],
} satisfies Meta<typeof Input>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: '默认', render: () => <Field defaultValue="经营分析助手" /> };

export const Empty: Story = { name: '空', render: () => <Field placeholder="例如：经营分析助手" /> };

export const Hover: Story = {
  name: '悬停',
  parameters: hover('[data-slot=input]'),
  render: () => <Field defaultValue="经营分析助手" />,
};

export const Focus: Story = {
  name: '聚焦',
  parameters: focus('[data-slot=input]'),
  render: () => <Field defaultValue="经营分析助手" />,
};

export const Disabled: Story = { name: '禁用', render: () => <Field defaultValue="经营分析助手" disabled /> };

export const ReadOnly: Story = {
  name: '只读',
  render: () => <Field defaultValue="agt_01JB7Q2M9X" readOnly />,
};

export const Error: Story = {
  name: '错误',
  render: () => <Field defaultValue="经营分析助手" error="同一项目中已有同名的智能体" />,
};

export const LongContent: Story = {
  name: '长内容',
  render: () => (
    <Field defaultValue="华东大区经营分析助手（生产环境，只读数据源，按周汇总的品类明细与政策解读）" />
  ),
};

export const Sizes: Story = {
  name: '尺寸',
  render: () => (
    <div className="flex flex-col gap-3">
      <Input size="sm" aria-label="小号输入框" placeholder="sm 28px" />
      <Input size="md" aria-label="中号输入框" placeholder="md 32px" />
    </div>
  ),
};
