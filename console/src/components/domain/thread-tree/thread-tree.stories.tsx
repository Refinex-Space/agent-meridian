import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';

import { focus, hover, storyFrame } from '@/test/stories';

import { buildQ3Scenario, Q3_FIXED_START } from '@mocks/fixtures/q3-session';

import { buildTimeline } from '../session-timeline';

import { threadTreeItems } from './from-timeline';
import { ThreadTree, type ThreadTreeItem } from './thread-tree';

const scenario = buildQ3Scenario(Q3_FIXED_START);
const Q3_ITEMS = threadTreeItems(buildTimeline(scenario.turnOne.slice(0, 40)), 'running');

const NESTED: ThreadTreeItem[] = [
  { id: 'main', parentId: null, label: '主线程', status: 'requires_action' },
  { id: 'thr_a', parentId: 'main', label: 'data-analyst', status: 'running' },
  { id: 'thr_c', parentId: 'thr_a', label: 'sql-writer', status: 'idle' },
  { id: 'thr_b', parentId: 'main', label: 'policy-reader', status: 'failure', note: '预算耗尽' },
  { id: 'thr_d', parentId: 'main', label: 'report-formatter', status: 'rescheduled' },
];

function Interactive({ items }: { items: readonly ThreadTreeItem[] }) {
  const [selected, setSelected] = useState<string | null>(items[0]?.id ?? null);
  return <ThreadTree items={items} selectedId={selected} onSelect={setSelected} />;
}

/** 线程树：主线程与子线程的层级、状态符号与异常结束原因；方向键导航，回车选择。 */
const meta = {
  title: '领域组件/ThreadTree',
  component: ThreadTree,
  decorators: [storyFrame('w-52')],
  args: { items: NESTED, selectedId: 'main', onSelect: () => undefined },
} satisfies Meta<typeof ThreadTree>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: '默认', render: (args) => <Interactive items={args.items} /> };

export const FromSession: Story = { name: '原型会话', render: () => <Interactive items={Q3_ITEMS} /> };

export const Hover: Story = { name: '悬停', parameters: hover('[role=treeitem] > div') };

export const Focus: Story = { name: '聚焦', parameters: focus('[role=treeitem][aria-level="2"]') };

export const LongContent: Story = {
  name: '长内容',
  args: {
    items: [
      { id: 'main', parentId: null, label: '主线程', status: 'running' },
      {
        id: 'thr_long',
        parentId: 'main',
        label: 'quarterly-sales-decline-root-cause-analyst',
        status: 'failure',
        note: '达到推理次数上限',
      },
    ],
  },
};
