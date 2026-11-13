import type { Meta, StoryObj } from '@storybook/react-vite';

import { focus, hover, storyFrame } from '@/test/stories';

import { Checkbox } from './checkbox';
import { Label } from './label';

function Options({ disabled = false, invalid = false }: { disabled?: boolean; invalid?: boolean }) {
  return (
    <div className="flex flex-col gap-3">
      {(
        [
          ['read', '读取工作区文件', false],
          ['bash', '执行 bash 命令', true],
          ['web', '访问外部网页', 'indeterminate'],
        ] as const
      ).map(([id, label, checked]) => (
        <div key={id} className="flex items-center gap-2">
          <Checkbox
            id={`cb-${id}`}
            defaultChecked={checked}
            disabled={disabled}
            aria-invalid={invalid || undefined}
          />
          <Label htmlFor={`cb-${id}`}>{label}</Label>
        </div>
      ))}
    </div>
  );
}

/** 复选框：未选、选中、部分选中（表格的全选）三种值。 */
const meta = {
  title: '基元/复选框',
  component: Checkbox,
  decorators: [storyFrame('w-80')],
} satisfies Meta<typeof Checkbox>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: '默认', render: () => <Options /> };

export const Hover: Story = {
  name: '悬停',
  parameters: hover('[data-slot=checkbox]'),
  render: () => <Options />,
};

export const Focus: Story = {
  name: '聚焦',
  parameters: focus('#cb-read'),
  render: () => <Options />,
};

export const Disabled: Story = { name: '禁用', render: () => <Options disabled /> };

export const Error: Story = {
  name: '错误',
  render: () => (
    <div className="flex flex-col gap-2">
      <Options invalid />
      <p className="text-12 text-danger">至少选择一项能力</p>
    </div>
  ),
};

export const LongContent: Story = {
  name: '长内容',
  render: () => (
    <div className="flex items-start gap-2">
      <span className="flex h-5 items-center">
        <Checkbox id="cb-long" defaultChecked />
      </span>
      <Label htmlFor="cb-long" className="items-start">
        允许子智能体继承父智能体的工具权限（仍受子智能体自己的权限规则约束，无人审批时询问改为拒绝）
      </Label>
    </div>
  ),
};
