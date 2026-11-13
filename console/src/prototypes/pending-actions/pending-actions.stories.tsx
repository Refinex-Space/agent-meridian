import type { Meta, StoryObj } from '@storybook/react-vite';

import { ACTION_EXPORT } from '@mocks/fixtures/q3-session';
import { DECIDERS, PENDING_ACTION_COUNT } from '@mocks/fixtures/pending-actions';
import { projectHandlers, type ProjectOptions } from '@mocks/handlers/project';

import { AppShell } from '../shell/app-shell';

import { PendingActions, type PendingActionsProps } from './pending-actions';

/** `navPending`：导航上的待处理计数，与故事的数据一致（全部处理完时为 0）。 */
function Page({
  navPending = PENDING_ACTION_COUNT,
  ...props
}: Omit<PendingActionsProps, 'deciders'> & { navPending?: number }) {
  return (
    <AppShell active="pending" pendingCount={navPending} breadcrumb={[{ label: '待处理' }]}>
      <PendingActions deciders={DECIDERS} {...props} />
    </AppShell>
  );
}

/**
 * 待处理（列表页模板加标签页）：待回应的动作行内批准或拒绝，点行在抽屉里看完整入参并选择记住此决定；历史列出结论与处理人。
 * 数据是 `GET /v1/pending-actions` 的 PendingAction，回应经 `POST /v1/sessions/{id}/events`，都由 MSW 模拟。
 */
const meta = {
  title: '原型/待处理',
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

export const Pending: Story = { name: '待回应', ...scenario({}) };

export const Detail: Story = {
  name: '详情与记住此决定',
  args: { initialSelected: ACTION_EXPORT },
  ...scenario({}),
};

export const History: Story = { name: '历史', args: { initialTab: 'history' }, ...scenario({}) };

export const AllClear: Story = {
  name: '全部处理完',
  args: { navPending: 0 },
  ...scenario({ mode: 'empty' }),
};

export const Loading: Story = { name: '加载中', ...scenario({ mode: 'loading' }) };

export const LoadFailed: Story = { name: '加载失败', ...scenario({ mode: 'error' }) };

export const NoPermission: Story = {
  name: '无回应权限',
  args: { canRespond: false, initialSelected: ACTION_EXPORT },
  ...scenario({}),
};
