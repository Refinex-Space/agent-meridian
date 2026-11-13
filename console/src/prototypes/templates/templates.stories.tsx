import type { Meta, StoryObj } from '@storybook/react-vite';

import { PENDING_ACTION_COUNT } from '@mocks/fixtures/pending-actions';
import { MODEL_NAMES, Q3_SESSION_ID } from '@mocks/fixtures/q3-session';
import { q3Handlers } from '@mocks/handlers/q3';

import { AgentDetail } from '../agents/agent-detail';
import { AgentList } from '../agents/agent-list';
import { EnvironmentEdit } from '../environments/environment-edit';
import { AGENT_ROWS, Q3_AGENT_DRAFT, Q3_VERSIONS } from '../fixtures/agent-definition';
import { PROD_READONLY, PROD_READONLY_META } from '../fixtures/environment';
import { SessionDetail } from '../session-detail/session-detail';
import { AppShell } from '../shell/app-shell';

/**
 * 全控制台只有四种页面模板（frontend-design.md「页面模板」），新页面必须选其一。每个模板配一个真实页面的内容：
 * 列表页是智能体列表，详情页是智能体详情，编辑页是环境，工作台是会话详情。T21 以这四个故事与应用外壳建立截图基线。
 */
const meta = {
  title: '原型/页面模板',
  parameters: { layout: 'fullscreen' },
} satisfies Meta;

export default meta;
type Story = StoryObj<typeof meta>;

const q3 = AGENT_ROWS.find((a) => a.name === '经营分析助手') ?? AGENT_ROWS[0]!;

export const ListTemplate: Story = {
  name: '列表页',
  render: () => (
    <AppShell active="agents" pendingCount={PENDING_ACTION_COUNT} breadcrumb={[{ label: '智能体' }]}>
      <AgentList agents={AGENT_ROWS} />
    </AppShell>
  ),
};

export const DetailTemplate: Story = {
  name: '详情页',
  render: () => (
    <AppShell
      active="agents"
      pendingCount={PENDING_ACTION_COUNT}
      breadcrumb={[{ label: '智能体', href: '/agents' }, { label: q3.name }]}
    >
      <AgentDetail agent={q3} definition={Q3_AGENT_DRAFT} versions={Q3_VERSIONS} />
    </AppShell>
  ),
};

export const EditTemplate: Story = {
  name: '编辑页',
  render: () => (
    <AppShell
      active="environments"
      pendingCount={PENDING_ACTION_COUNT}
      breadcrumb={[
        { label: '环境', href: '/environments' },
        { label: PROD_READONLY.name, href: '/environments/prod' },
        { label: '编辑' },
      ]}
    >
      <EnvironmentEdit environment={PROD_READONLY} meta={PROD_READONLY_META} />
    </AppShell>
  ),
};

export const WorkbenchTemplate: Story = {
  name: '工作台',
  beforeEach({ msw }) {
    msw.use(...q3Handlers('finished'));
  },
  render: () => (
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
  ),
};
