import type { Meta, StoryObj } from '@storybook/react-vite';
import { userEvent, within } from 'storybook/test';

import { focus, storyFrame } from '@/test/stories';

import { ApprovalForm } from './approval-form';

/** 工具调用的审批：批准或拒绝，可选记住此决定与一条建议规则。会话检查器与待处理页共用。 */
const meta = {
  title: '领域组件/ApprovalForm',
  component: ApprovalForm,
  decorators: [storyFrame('w-80', 'pt-16')],
  args: {
    actionId: 'act_01JCQ9C6W2Y5A8D1F4H7K0M3PS',
    toolName: 'bash',
    suggestedRules: ['python scripts/export_decline.py:*', 'python:*'],
    onDecide: () => undefined,
  },
} satisfies Meta<typeof ApprovalForm>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: '默认' };

export const Remember: Story = {
  name: '记住此决定',
  play: async ({ canvasElement }) => {
    await userEvent.click(within(canvasElement).getByRole('radio', { name: '本项目内不再询问' }));
  },
};

export const Focus: Story = {
  name: '聚焦',
  parameters: focus('[data-slot=button]:first-of-type'),
};

export const Pending: Story = { name: '提交中', args: { pending: 'approve' } };

export const NoPermission: Story = {
  name: '无权限',
  args: { deniedReason: '需要回应待处理动作的权限，请联系项目管理员' },
};

export const LongRule: Story = {
  name: '长内容',
  args: {
    toolName: 'web_fetch',
    suggestedRules: ['domain:intranet.east-retail.example.com/policies/procurement/2026'],
  },
  play: async ({ canvasElement }) => {
    await userEvent.click(within(canvasElement).getByRole('radio', { name: '本会话内不再询问' }));
  },
};
