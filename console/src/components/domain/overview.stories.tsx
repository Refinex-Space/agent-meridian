import type { Meta, StoryObj } from '@storybook/react-vite';
import type { ReactNode } from 'react';

import { Button } from '@/components/ui/button';
import { PlusIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { ApiError } from '@/lib/api/sessions';
import type { EventOf, SessionEvent } from '@/lib/api/types';

import { buildQ3Scenario, MODEL_NAMES, Q3_FIXED_START } from '@mocks/fixtures/q3-session';
import { buildSessionList, SESSION_LIST_NOW } from '@mocks/fixtures/session-list';

import { CodeBlock } from './code-block';
import { CopyableId } from './copyable-id';
import { DataTable } from './data-table';
import { sessionColumns } from './data-table/examples';
import { EmptyState } from './empty-state';
import { ErrorState } from './error-state';
import { EventCard } from './event-card';
import { emptyValues, FilterBar, type FilterDef } from './filter-bar';
import { InspectorPanel } from './inspector-panel';
import { KeyValueList } from './key-value-list';
import { PageHeader } from './page-header';
import { PermissionGate } from './permission-gate';
import { RelativeTime } from './relative-time';
import { StatusBadge } from './status-badge';
import { StreamIndicator } from './stream-indicator';
import { ThreadTree } from './thread-tree';
import { TokenUsage } from './token-usage';
import { ToolCallView } from './tool-call-view';

const scenario = buildQ3Scenario(Q3_FIXED_START);
const EVENTS: SessionEvent[] = [...scenario.turnOne, ...scenario.turnTwo];
const use = EVENTS.find(
  (e) => e.type === 'agent.tool_use' && e.data.name === 'knowledge_search',
) as EventOf<'agent.tool_use'>;
const result = EVENTS.find(
  (e) => e.type === 'agent.tool_result' && e.data.tool_use_id === use.data.tool_use_id,
) as EventOf<'agent.tool_result'>;
const pending = EVENTS.find((e) => e.type === 'session.status_requires_action') as SessionEvent;
const reply = EVENTS.find((e) => e.type === 'agent.message') as SessionEvent;
const before = (e: SessionEvent) => EVENTS.slice(0, EVENTS.indexOf(e));
const ROWS = buildSessionList(6);
const DEFS: FilterDef[] = [
  {
    key: 'status',
    label: '状态',
    multiple: true,
    options: [
      { value: 'running', label: '运行中' },
      { value: 'idle', label: '空闲' },
    ],
  },
];
const USAGE = {
  input_tokens: 48_210,
  output_tokens: 6_104,
  cached_tokens: 31_000,
  cache_creation_tokens: 0,
  reasoning_tokens: 2_380,
};

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-4">
      <h2 className="text-16 leading-heading font-semibold">{title}</h2>
      {children}
    </section>
  );
}

/** 全部领域组件放在一页，对照 T20a 确认的方向检查颜色、字体、间距、圆角与阴影是否同一套。 */
function Overview() {
  return (
    <div className="flex min-h-screen flex-col gap-12 bg-canvas p-8 text-ink">
      <h1 className="text-22 leading-heading font-semibold">领域组件总览</h1>

      <Tabs defaultValue="summary" className="rounded-panel border border-rule bg-surface px-6">
        <PageHeader
          title="经营分析助手"
          breadcrumb={[{ label: '智能体', href: '#agents' }, { label: '经营分析助手' }]}
          meta={
            <>
              <StatusBadge of="custom" kind="success" label="已发布 v12" />
              <CopyableId id="agt_01JB7Q2M9XK4" label="智能体 ID" />
              <span>
                更新于 <RelativeTime value="2026-11-12T16:40:00+08:00" now={SESSION_LIST_NOW} />
              </span>
              <StreamIndicator state="open" />
            </>
          }
          actions={
            <>
              <PermissionGate allowed={false} mode="disable" reason="需要编辑智能体的权限，请联系项目管理员">
                <Button>编辑</Button>
              </PermissionGate>
              <Button variant="primary">发布版本</Button>
            </>
          }
        >
          <TabsList>
            <TabsTrigger value="summary">概要</TabsTrigger>
            <TabsTrigger value="versions">版本</TabsTrigger>
            <TabsTrigger value="sessions">会话</TabsTrigger>
          </TabsList>
        </PageHeader>
        <TabsContent value="versions" className="py-6 text-13 text-ink-2">
          版本历史
        </TabsContent>
        <TabsContent value="sessions" className="py-6 text-13 text-ink-2">
          最近的会话
        </TabsContent>
        <TabsContent value="summary" className="grid grid-cols-[minmax(0,1fr)_320px] gap-12 py-6">
          <KeyValueList
            items={[
              { label: '模型', value: 'qwen-plus', mono: true },
              { label: '环境', value: '生产只读 r3' },
              { label: '档位', value: 'sandbox', mono: true, hint: 'sandbox 档在独立的会话沙箱中执行命令' },
              { label: '子智能体', value: null },
            ]}
          />
          <TokenUsage variant="detail" usage={USAGE} cost={{ amount: 1.237, currency: 'CNY' }} />
        </TabsContent>
      </Tabs>

      <Section title="状态">
        <div className="flex flex-wrap items-center gap-x-6 gap-y-2 rounded-panel border border-rule bg-surface p-4">
          <StatusBadge of="session" status="running" />
          <StatusBadge of="session" status="requires_action" />
          <StatusBadge of="session" status="idle" />
          <StatusBadge of="session" status="rescheduled" />
          <StatusBadge of="session" status="terminated" />
          <StatusBadge of="tool" status="success" />
          <StatusBadge of="tool" status="denied" />
          <StreamIndicator state="reconnecting" />
          <TokenUsage usage={USAGE} />
        </div>
      </Section>

      <Section title="列表">
        <div className="rounded-panel border border-rule bg-surface px-4">
          <DataTable
            label="会话列表"
            columns={sessionColumns(SESSION_LIST_NOW)}
            data={ROWS}
            getRowId={(row) => row.session.id}
            activeRowId={ROWS[1]?.session.id}
            onRowClick={() => undefined}
            toolbar={
              <FilterBar
                filters={DEFS}
                value={emptyValues(DEFS)}
                onChange={() => undefined}
                search={{ label: '搜索会话', placeholder: '会话 ID 或终端用户' }}
                resultCount={ROWS.length}
              />
            }
          />
        </div>
      </Section>

      <Section title="会话详情的部件">
        <div className="grid grid-cols-[208px_minmax(0,1fr)_344px] overflow-hidden rounded-panel border border-rule bg-surface">
          <div className="border-r border-rule p-3">
            <ThreadTree
              items={[
                { id: 'main', parentId: null, label: '主线程', status: 'requires_action' },
                { id: 'thr_a', parentId: 'main', label: 'data-analyst', status: 'idle' },
                {
                  id: 'thr_b',
                  parentId: 'main',
                  label: 'policy-reader',
                  status: 'failure',
                  note: '预算耗尽',
                },
              ]}
              selectedId="main"
              onSelect={() => undefined}
            />
          </div>
          <div className="flex flex-col gap-2 p-4">
            <EventCard event={result} context={before(result)} modelNames={MODEL_NAMES} />
            <EventCard event={reply} context={before(reply)} modelNames={MODEL_NAMES} />
            <EventCard
              event={pending}
              context={before(pending)}
              modelNames={MODEL_NAMES}
              selected
              onSelect={() => undefined}
            />
          </div>
          <InspectorPanel title="调用 knowledge_search" raw={result} className="h-160 border-l border-rule">
            <ToolCallView use={use} result={result} />
          </InspectorPanel>
        </div>
      </Section>

      <Section title="代码">
        <CodeBlock
          className="max-w-200"
          code={JSON.stringify(
            { command: 'python export.py --quarter 2026Q3', timeout: 600, dry_run: false },
            null,
            2,
          )}
          language="json"
          label="入参"
        />
      </Section>

      <Section title="空态、错误与无权限">
        <div className="grid grid-cols-3 gap-4">
          <div className="rounded-panel border border-rule bg-surface">
            <EmptyState
              variant="first-use"
              title="还没有智能体，创建一个开始调试会话"
              action={
                <Button variant="primary" icon={<Icon glyph={PlusIcon} />}>
                  创建智能体
                </Button>
              }
            />
          </div>
          <div className="rounded-panel border border-rule bg-surface">
            <ErrorState
              title="无法加载会话列表"
              error={
                new ApiError(503, {
                  type: 'urn:meridian:error:internal.unavailable',
                  title: 'Service Unavailable',
                  status: 503,
                  detail: '运行时节点正在重启。',
                  instance: '/v1/sessions',
                  code: 'internal.unavailable',
                  request_id: 'req_01JC9A7M2KX4Q8',
                })
              }
              onRetry={() => undefined}
            />
          </div>
          <div className="rounded-panel border border-rule bg-surface">
            <PermissionGate
              allowed={false}
              mode="explain"
              title="没有权限查看凭据"
              reason="查看凭据需要租户管理员或所有者角色，请联系租户管理员。"
            />
          </div>
        </div>
      </Section>
    </div>
  );
}

const meta = {
  title: '领域组件/总览',
  component: Overview,
} satisfies Meta<typeof Overview>;

export default meta;

export const All: StoryObj<typeof meta> = { name: '总览' };
