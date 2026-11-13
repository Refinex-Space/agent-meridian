import type { Meta, StoryObj } from '@storybook/react-vite';

import { focus, hover, storyFrame } from '@/test/stories';

import { Label } from './label';
import { RadioGroup, RadioGroupItem } from './radio-group';

const OPTIONS = [
  ['none', '只这一次'],
  ['session', '本会话内不再询问'],
  ['project', '本项目内不再询问'],
] as const;

function Options({ disabled = false, invalid = false }: { disabled?: boolean; invalid?: boolean }) {
  return (
    <RadioGroup
      defaultValue="session"
      disabled={disabled}
      aria-label="记住此决定"
      aria-invalid={invalid || undefined}
    >
      {OPTIONS.map(([value, label]) => (
        <div key={value} className="flex items-center gap-2">
          <RadioGroupItem id={`rg-${value}`} value={value} aria-invalid={invalid || undefined} />
          <Label htmlFor={`rg-${value}`}>{label}</Label>
        </div>
      ))}
    </RadioGroup>
  );
}

/** 单选：选项不超过 5 个时用它。 */
const meta = {
  title: '基元/单选',
  component: RadioGroup,
  decorators: [storyFrame('w-80')],
} satisfies Meta<typeof RadioGroup>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: '默认', render: () => <Options /> };

export const Hover: Story = {
  name: '悬停',
  parameters: hover('[data-slot=radio-group-item]'),
  render: () => <Options />,
};

export const Focus: Story = {
  name: '聚焦',
  parameters: focus('#rg-session'),
  render: () => <Options />,
};

export const Disabled: Story = { name: '禁用', render: () => <Options disabled /> };

export const Error: Story = {
  name: '错误',
  render: () => (
    <div className="flex flex-col gap-2">
      <Options invalid />
      <p className="text-12 text-danger">请选择记住的范围</p>
    </div>
  ),
};

export const LongContent: Story = {
  name: '长内容',
  render: () => (
    <RadioGroup defaultValue="a" aria-label="出网策略">
      <div className="flex items-start gap-2">
        <span className="flex h-5 items-center">
          <RadioGroupItem id="rg-long" value="a" />
        </span>
        <Label htmlFor="rg-long" className="items-start">
          只允许访问白名单中的域名，其余请求由出网代理拒绝并写入审计日志
        </Label>
      </div>
    </RadioGroup>
  ),
};
