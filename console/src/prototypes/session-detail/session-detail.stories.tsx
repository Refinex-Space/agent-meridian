import type { Meta, StoryObj } from '@storybook/react-vite';

import { PENDING_ACTION_COUNT } from '@mocks/fixtures/pending-actions';
import { MODEL_NAMES, Q3_SESSION_ID } from '@mocks/fixtures/q3-session';
import { q3Handlers, type Q3Mode } from '@mocks/handlers/q3';

import { AppShell } from '../shell/app-shell';
import { SessionDetail } from './session-detail';

function Page() {
  return (
    <AppShell
      active="sessions"
      pendingCount={PENDING_ACTION_COUNT}
      breadcrumb={[
        { label: '会话', href: '/sessions' },
        { label: Q3_SESSION_ID, mono: true },
      ]}
    >
      <SessionDetail sessionId={Q3_SESSION_ID} modelNames={MODEL_NAMES} />
    </AppShell>
  );
}

const meta = {
  title: '原型/会话详情',
  component: Page,
  parameters: { layout: 'fullscreen' },
} satisfies Meta<typeof Page>;

export default meta;
type Story = StoryObj<typeof meta>;

function scenario(mode: Q3Mode): Pick<Story, 'beforeEach'> {
  return {
    beforeEach({ msw }) {
      msw.use(...q3Handlers(mode));
    },
  };
}

/** 打开页面后第 2 轮实时推送：用户消息、推理、调用 bash，停在等待批准；批准后执行导出，回复逐字出现。 */
export const Live: Story = { name: '运行中（实时追加）', ...scenario('live') };

/** 停在等待批准：检查器中可以选择记住此决定。 */
export const AwaitingAction: Story = { name: '等待处理', ...scenario('awaiting') };

export const Finished: Story = { name: '已结束', ...scenario('finished') };

export const Loading: Story = { name: '加载中', ...scenario('loading') };

export const LoadFailed: Story = { name: '加载失败', ...scenario('error') };
