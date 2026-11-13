import type { Meta, StoryObj } from '@storybook/react-vite';

import { Checkbox } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { focus, hover, storyFrame } from '@/test/stories';

import { fieldAria, FormField } from './form-field';

/** 表单字段：标签在上、控件、错误在下；补充说明在 info 提示里。智能体与环境的编辑页用它。 */
const meta = {
  title: '领域组件/FormField',
  component: FormField,
  decorators: [storyFrame('w-100')],
  args: {
    id: 'agent-name',
    label: '名称',
    children: <Input id="agent-name" defaultValue="support-assistant" />,
  },
} satisfies Meta<typeof FormField>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: '默认' };

export const WithHint: Story = {
  name: '带说明',
  args: { hint: '项目内唯一，发布时写进规格，也是日志里的智能体名' },
};

export const HintHover: Story = {
  name: '说明悬停',
  args: { hint: '项目内唯一，发布时写进规格，也是日志里的智能体名' },
  parameters: hover('button[aria-label="名称的说明"]'),
};

export const HintFocus: Story = {
  name: '说明聚焦',
  args: { hint: '项目内唯一，发布时写进规格，也是日志里的智能体名' },
  parameters: focus('button[aria-label="名称的说明"]'),
};

export const Invalid: Story = {
  name: '错误',
  args: {
    error: '小写字母开头，只用小写字母、数字、下划线与短横线，至多 64 个字符。',
    children: <Input id="agent-name" defaultValue="Support Assistant" {...fieldAria('agent-name', 'x')} />,
  },
};

export const Group: Story = {
  name: '控件组',
  args: {
    id: 'tools',
    label: '平台工具',
    group: true,
    children: (
      <div role="group" aria-labelledby="tools-label" className="flex gap-6">
        {['read_file', 'write_file'].map((tool) => (
          <div key={tool} className="flex items-center gap-2">
            <Checkbox id={`tool-${tool}`} defaultChecked={tool === 'read_file'} />
            <Label htmlFor={`tool-${tool}`} className="font-mono text-12 font-normal">
              {tool}
            </Label>
          </div>
        ))}
      </div>
    ),
  },
};
