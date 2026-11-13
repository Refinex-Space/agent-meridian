import type { Meta, StoryObj } from '@storybook/react-vite';

import { PENDING_ACTION_COUNT } from '@mocks/fixtures/pending-actions';
import { projectHandlers, type ProjectOptions } from '@mocks/handlers/project';

import { AppShell } from '../shell/app-shell';

import { Overview, type OverviewProps } from './overview';

/** `navPending`：导航上的待处理计数，与故事的数据一致（新项目为 0）。 */
function Page({ navPending = PENDING_ACTION_COUNT, ...props }: OverviewProps & { navPending?: number }) {
  return (
    <AppShell active="overview" pendingCount={navPending} breadcrumb={[{ label: '概览' }]}>
      <Overview {...props} />
    </AppShell>
  );
}

/**
 * 概览（详情页模板）：运行状况、最近 14 天的用量与本月预算、按智能体的失败率与最近失败的轮次。运行概况是
 * `GET /v1/session-stats`（MSW 模拟），用量与预算是视图模型（管理 API 在 T21 接入）。
 */
const meta = {
  title: '原型/概览',
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

export const NewProject: Story = {
  name: '新项目',
  args: { usage: 'empty', navPending: 0 },
  ...scenario({ mode: 'empty' }),
};

export const Loading: Story = {
  name: '加载中',
  args: { usage: 'loading' },
  ...scenario({ mode: 'loading' }),
};

/** 用量接口失败只影响用量与预算两块，运行状况照常。 */
export const UsageFailed: Story = { name: '部分加载失败', args: { usage: 'error' }, ...scenario({}) };

export const NoUsagePermission: Story = {
  name: '无用量权限',
  args: { canReadUsage: false },
  ...scenario({}),
};
