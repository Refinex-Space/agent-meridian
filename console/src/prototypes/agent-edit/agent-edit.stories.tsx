import type { Meta, StoryObj } from '@storybook/react-vite';

import { ApiError } from '@/lib/api/sessions';

import { PENDING_ACTION_COUNT } from '@mocks/fixtures/pending-actions';

import { Q3_AGENT_DRAFT, Q3_DRAFT_META } from '../fixtures/agent-definition';
import { AppShell } from '../shell/app-shell';

import { AgentEdit, type AgentEditProps } from './agent-edit';

function Page(props: AgentEditProps) {
  return (
    <AppShell
      active="agents"
      pendingCount={PENDING_ACTION_COUNT}
      breadcrumb={[
        { label: '智能体', href: '/agents' },
        { label: '经营分析助手', href: '/agents/q3' },
        { label: '编辑' },
      ]}
    >
      <AgentEdit {...props} />
    </AppShell>
  );
}

/**
 * 智能体编辑（编辑页模板）：经营分析助手 v13 的草稿。数据是视图模型（`src/prototypes/fixtures/agent-definition.ts`），
 * 管理 API 的类型在 T21 生成后由 T22 编写映射。
 */
const meta = {
  title: '原型/智能体编辑',
  component: Page,
  parameters: { layout: 'fullscreen' },
  args: { draft: Q3_AGENT_DRAFT, meta: Q3_DRAFT_META },
} satisfies Meta<typeof Page>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Saved: Story = { name: '已保存' };

export const Dirty: Story = {
  name: '有未保存的更改',
  args: { initialEdits: { temperature: 0.2, prefetchTopK: 8 } },
};

export const Invalid: Story = {
  name: '校验错误',
  args: {
    showErrors: true,
    initialEdits: {
      temperature: 2.4,
      subagents: Q3_AGENT_DRAFT.subagents.map((s, i) =>
        i === 1 ? { ...s, tools: [...s.tools, 'web_fetch'] } : s,
      ),
    },
  },
};

export const ReadOnly: Story = { name: '只读', args: { canEdit: false } };

export const Loading: Story = { name: '加载中', args: { draft: null, loading: true } };

export const LoadFailed: Story = {
  name: '加载失败',
  args: {
    draft: null,
    loadError: new ApiError(404, {
      type: 'urn:meridian:error:agent_version.not_found',
      title: 'Not Found',
      status: 404,
      detail: '版本 13 不存在，可能已被其他成员删除。',
      instance:
        '/admin/v1/tenants/tnt_01JB5H1N7R3V9X5A1C7E3G9JM/projects/prj_01JB6M2Q8T4W0Y6B2D8F4H0KQ/agents/agt_01JBX4E2R8M6T1K9V3P7W5Y0QA/versions/13',
      code: 'agent_version.not_found',
      request_id: 'req_01JCQC2E5G8J1L4N7Q0S3V6X9Z',
    }),
  },
};
