import type { Meta, StoryObj } from '@storybook/react-vite';
import type { ReactNode } from 'react';

import { storyFrame } from '@/test/stories';

import { StatusBadge } from './status-badge';

function Row({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="grid grid-cols-[96px_minmax(0,1fr)] items-center gap-4">
      <span className="text-12 text-ink-2">{title}</span>
      <div className="flex flex-wrap items-center gap-x-6 gap-y-2">{children}</div>
    </div>
  );
}

/**
 * 状态徽标：会话、轮次、工具调用、待处理动作的状态，符号与文字来自统一的映射表。不可交互（没有悬停、聚焦、禁用）。
 */
const meta = {
  title: '领域组件/StatusBadge',
  component: StatusBadge,
  decorators: [storyFrame()],
} satisfies Meta<typeof StatusBadge>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  name: '默认',
  args: { of: 'session', status: 'running' },
  render: () => (
    <div className="flex flex-col gap-4">
      <Row title="会话">
        <StatusBadge of="session" status="running" />
        <StatusBadge of="session" status="requires_action" />
        <StatusBadge of="session" status="idle" />
        <StatusBadge of="session" status="rescheduled" />
        <StatusBadge of="session" status="terminated" />
      </Row>
      <Row title="轮次">
        <StatusBadge of="turn" status="running" />
        <StatusBadge of="turn" status="end_turn" />
        <StatusBadge of="turn" status="interrupted" />
        <StatusBadge of="turn" status="budget_exceeded" />
      </Row>
      <Row title="工具调用">
        <StatusBadge of="tool" status="awaiting" />
        <StatusBadge of="tool" status="running" />
        <StatusBadge of="tool" status="success" />
        <StatusBadge of="tool" status="denied" />
        <StatusBadge of="tool" status="error" />
      </Row>
      <Row title="待处理动作">
        <StatusBadge of="action" status="pending" />
        <StatusBadge
          of="action"
          status="resolved"
          conclusion={{
            origin: 'caller',
            approved: true,
            deny_reason: null,
            remember_scope: null,
            remember_rule: null,
            decision: null,
            error: null,
          }}
        />
        <StatusBadge
          of="action"
          status="resolved"
          conclusion={{
            origin: 'timeout',
            approved: false,
            deny_reason: null,
            remember_scope: null,
            remember_rule: null,
            decision: null,
            error: null,
          }}
        />
        <StatusBadge of="action" status="expired" />
      </Row>
    </div>
  ),
};

export const Small: Story = {
  name: '小号',
  args: { of: 'session', status: 'running', size: 'sm' },
};

export const LongContent: Story = {
  name: '长内容',
  args: { of: 'turn', status: 'tools_denied' },
  decorators: [storyFrame('w-40')],
  render: (args) => <StatusBadge {...args} className="max-w-full truncate" />,
};
