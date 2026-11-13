import type { Meta, StoryObj } from '@storybook/react-vite';

import { AGENT_STORE_PATROL } from '@mocks/fixtures/agents';
import { PENDING_ACTION_COUNT } from '@mocks/fixtures/pending-actions';
import { projectHandlers, type ProjectOptions } from '@mocks/handlers/project';

import { AppShell } from '../shell/app-shell';

import { SessionList, type SessionListProps } from './session-list';

/** `navPending`：导航上的待处理计数，与故事的数据一致（没有会话时为 0）。 */
function Page({ navPending = PENDING_ACTION_COUNT, ...props }: SessionListProps & { navPending?: number }) {
  return (
    <AppShell active="sessions" pendingCount={navPending} breadcrumb={[{ label: '会话' }]}>
      <SessionList {...props} />
    </AppShell>
  );
}

/**
 * 会话列表（列表页模板）：筛选与搜索、游标加载更多、轮询提示"有 N 条更新"。数据是 `GET /v1/sessions` 的 Session，
 * 智能体名取自 `GET /v1/agents`，都由 MSW 模拟。
 */
const meta = {
  title: '原型/会话列表',
  component: Page,
  parameters: { layout: 'fullscreen' },
} satisfies Meta<typeof Page>;

export default meta;
type Story = StoryObj<typeof meta>;

function scenario(options: ProjectOptions): Pick<Story, 'beforeEach'> {
  return {
    beforeEach({ msw }) {
      msw.use(...projectHandlers(options));
    },
  };
}

export const Default: Story = { name: '默认', ...scenario({}) };

/** 4 秒后轮询到 3 条变化：表头上方提示，点击后刷新。 */
export const Live: Story = {
  name: '实时更新',
  args: { pollMs: 4_000 },
  ...scenario({ live: true }),
};

export const Filtered: Story = {
  name: '按条件筛选',
  args: {
    initialFilters: { q: '', filters: { agent_id: [AGENT_STORE_PATROL.id], status: [], created: ['7d'] } },
  },
  ...scenario({}),
};

export const FilteredEmpty: Story = {
  name: '筛选为空',
  args: {
    initialFilters: { q: 'u_0000', filters: { agent_id: [], status: ['rescheduled'], created: ['1h'] } },
  },
  ...scenario({}),
};

export const LongContent: Story = { name: '长内容', ...scenario({ longContent: true }) };

export const FirstUse: Story = { name: '空', args: { navPending: 0 }, ...scenario({ mode: 'empty' }) };

export const Loading: Story = { name: '加载中', ...scenario({ mode: 'loading' }) };

export const LoadFailed: Story = { name: '加载失败', ...scenario({ mode: 'error' }) };

export const NoPermission: Story = { name: '无权限', args: { canRead: false }, ...scenario({}) };
