import type { Meta, StoryObj } from '@storybook/react-vite';
import { useState } from 'react';
import { userEvent, within } from 'storybook/test';

import { Button } from '@/components/ui/button';
import { ColumnsIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { focus, hover, storyFrame } from '@/test/stories';

import { FilterBar } from './filter-bar';
import { emptyValues, type FilterDef, type FilterValues } from './filters';
import { useUrlFilters } from './use-url-filters';

const DEFS: FilterDef[] = [
  {
    key: 'agent_id',
    label: '智能体',
    multiple: true,
    options: [
      { value: 'agt_01JB7Q2M9XK4', label: '经营分析助手' },
      { value: 'agt_01JB7R5T1WP8', label: '客服质检' },
      { value: 'agt_01JB7S8V4YQ2', label: '政策解读' },
    ],
  },
  {
    key: 'status',
    label: '状态',
    multiple: true,
    options: [
      { value: 'running', label: '运行中' },
      { value: 'requires_action', label: '等待处理' },
      { value: 'idle', label: '空闲' },
      { value: 'terminated', label: '已终止' },
    ],
  },
  {
    key: 'created_within',
    label: '创建时间',
    options: [
      { value: '1h', label: '最近 1 小时' },
      { value: '24h', label: '最近 24 小时' },
      { value: '7d', label: '最近 7 天' },
    ],
  },
];

const SEARCH = { label: '搜索会话', placeholder: '会话 ID 或终端用户' };

function Controlled({ initial }: { initial: FilterValues }) {
  const [value, setValue] = useState(initial);
  return (
    <FilterBar
      filters={DEFS}
      value={value}
      onChange={setValue}
      search={SEARCH}
      resultCount={128}
      actions={
        <Button size="icon-sm" variant="ghost" aria-label="显示的列">
          <Icon glyph={ColumnsIcon} />
        </Button>
      }
    />
  );
}

function UrlSynced() {
  const [value, setValue] = useUrlFilters(DEFS);
  return (
    <div className="flex flex-col gap-4">
      <FilterBar filters={DEFS} value={value} onChange={setValue} search={SEARCH} resultCount={128} />
      <code className="text-12 text-ink-2">{decodeURIComponent(window.location.search)}</code>
    </div>
  );
}

const ACTIVE: FilterValues = {
  q: 'u_82',
  filters: {
    agent_id: ['agt_01JB7Q2M9XK4'],
    status: ['running', 'requires_action'],
    created_within: ['24h'],
  },
};

/** 列表页的筛选与搜索：多选、单选与搜索，状态同步到 URL；有条件时出现"清除筛选"，按 / 聚焦搜索。 */
const meta = {
  title: '领域组件/FilterBar',
  component: FilterBar,
  decorators: [storyFrame('w-240')],
} satisfies Meta<typeof FilterBar>;

export default meta;
type Story = StoryObj<typeof meta>;

const base = { filters: DEFS, value: emptyValues(DEFS), onChange: () => undefined };

export const Default: Story = {
  name: '默认',
  args: base,
  render: () => <Controlled initial={emptyValues(DEFS)} />,
};

export const Active: Story = {
  name: '有筛选条件',
  args: base,
  render: () => <Controlled initial={ACTIVE} />,
};

export const Hover: Story = {
  name: '悬停',
  args: base,
  parameters: hover('[data-slot=button]'),
  render: () => <Controlled initial={ACTIVE} />,
};

export const Focus: Story = {
  name: '聚焦',
  args: base,
  parameters: focus('[data-slot=input]'),
  render: () => <Controlled initial={emptyValues(DEFS)} />,
};

export const Open: Story = {
  name: '展开多选',
  args: base,
  render: () => <Controlled initial={ACTIVE} />,
  play: async ({ canvasElement }) => {
    await userEvent.click(within(canvasElement).getByRole('button', { name: /^状态/ }));
  },
};

export const UrlSync: Story = { name: 'URL 同步', args: base, render: () => <UrlSynced /> };

export const LongContent: Story = {
  name: '长内容',
  args: base,
  decorators: [storyFrame('w-160')],
  render: () => (
    <Controlled
      initial={{
        q: 'ses_01JC8Z4V7QX3M9N2P8R5',
        filters: {
          agent_id: ['agt_01JB7Q2M9XK4', 'agt_01JB7R5T1WP8', 'agt_01JB7S8V4YQ2'],
          status: ['running', 'requires_action', 'idle'],
          created_within: ['7d'],
        },
      }}
    />
  ),
};
