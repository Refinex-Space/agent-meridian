import type { Meta, StoryObj } from '@storybook/react-vite';

import { focus, hover, storyFrame } from '@/test/stories';

import { Label } from './label';
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectTrigger,
  SelectValue,
} from './select';

const MODELS = [
  { value: 'mdl_qwen_plus', label: 'qwen-plus' },
  { value: 'mdl_qwen_max', label: 'qwen-max' },
  { value: 'mdl_deepseek_v3', label: 'deepseek-v3' },
  { value: 'mdl_glm_4', label: 'glm-4.6', disabled: true },
];

function Field({
  open,
  disabled,
  error,
  value,
  options = MODELS,
}: {
  open?: boolean;
  disabled?: boolean;
  error?: string;
  value?: string;
  options?: { value: string; label: string; disabled?: boolean }[];
}) {
  return (
    <div className="flex flex-col gap-1">
      <Label htmlFor="model">模型</Label>
      <Select defaultOpen={open} disabled={disabled} defaultValue={value}>
        <SelectTrigger
          id="model"
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? 'model-error' : undefined}
        >
          <SelectValue placeholder="选择模型" />
        </SelectTrigger>
        <SelectContent>
          <SelectGroup>
            <SelectLabel>对话模型</SelectLabel>
            {options.map((o) => (
              <SelectItem key={o.value} value={o.value} disabled={o.disabled}>
                {o.label}
              </SelectItem>
            ))}
          </SelectGroup>
        </SelectContent>
      </Select>
      {error && (
        <p id="model-error" className="text-12 text-danger">
          {error}
        </p>
      )}
    </div>
  );
}

/** 选择器：触发器与输入框同一外观；列表在触发器正下方展开、与触发器同宽。 */
const meta = {
  title: '基元/选择器',
  component: Select,
  decorators: [storyFrame('w-72')],
} satisfies Meta<typeof Select>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: '默认', render: () => <Field value="mdl_qwen_plus" /> };

export const Empty: Story = { name: '空', render: () => <Field /> };

export const Hover: Story = {
  name: '悬停',
  parameters: hover('[data-slot=select-trigger]'),
  render: () => <Field value="mdl_qwen_plus" />,
};

export const Focus: Story = {
  name: '聚焦',
  parameters: focus('[data-slot=select-trigger]'),
  render: () => <Field value="mdl_qwen_plus" />,
};

export const Open: Story = { name: '展开', render: () => <Field value="mdl_qwen_plus" open /> };

export const Disabled: Story = { name: '禁用', render: () => <Field value="mdl_qwen_plus" disabled /> };

export const Error: Story = { name: '错误', render: () => <Field error="请选择一个模型" /> };

export const LongContent: Story = {
  name: '长内容',
  render: () => (
    <Field
      open
      value="m3"
      options={Array.from({ length: 16 }, (_, i) => ({
        value: `m${i}`,
        label: i === 3 ? 'qwen-plus-2026-09-11-latest（华东一区专属部署，按量计费）' : `qwen-plus-${i + 1}`,
      }))}
    />
  ),
};
