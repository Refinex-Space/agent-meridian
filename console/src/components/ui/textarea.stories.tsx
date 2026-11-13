import type { Meta, StoryObj } from '@storybook/react-vite';
import type { ComponentProps } from 'react';

import { focus, hover, storyFrame } from '@/test/stories';

import { Label } from './label';
import { Textarea } from './textarea';

const PROMPT = '你是华东大区的经营分析助手。回答前先检索知识库中的政策文件，引用时写明文件名与条款。';

function Field({ error, ...props }: ComponentProps<typeof Textarea> & { error?: string }) {
  return (
    <div className="flex flex-col gap-1">
      <Label htmlFor="prompt">系统提示词</Label>
      <Textarea
        id="prompt"
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? 'prompt-error' : undefined}
        {...props}
      />
      {error && (
        <p id="prompt-error" className="text-12 text-danger">
          {error}
        </p>
      )}
    </div>
  );
}

/** 文本域：随内容增高，只允许竖向拉伸。 */
const meta = {
  title: '基元/文本域',
  component: Textarea,
  decorators: [storyFrame('w-120')],
} satisfies Meta<typeof Textarea>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: '默认', render: () => <Field defaultValue={PROMPT} /> };

export const Empty: Story = {
  name: '空',
  render: () => <Field placeholder="描述智能体的角色、能力边界与回答方式" />,
};

export const Hover: Story = {
  name: '悬停',
  parameters: hover('[data-slot=textarea]'),
  render: () => <Field defaultValue={PROMPT} />,
};

export const Focus: Story = {
  name: '聚焦',
  parameters: focus('[data-slot=textarea]'),
  render: () => <Field defaultValue={PROMPT} />,
};

export const Disabled: Story = { name: '禁用', render: () => <Field defaultValue={PROMPT} disabled /> };

export const ReadOnly: Story = { name: '只读', render: () => <Field defaultValue={PROMPT} readOnly /> };

export const Error: Story = {
  name: '错误',
  render: () => <Field defaultValue="" error="系统提示词不能为空" />,
};

export const LongContent: Story = {
  name: '长内容',
  render: () => <Field defaultValue={Array.from({ length: 8 }, () => PROMPT).join('\n')} />,
};
