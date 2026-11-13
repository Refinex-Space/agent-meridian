import type { Meta, StoryObj } from '@storybook/react-vite';
import { useEffect, useMemo, useState } from 'react';

import type { SessionEvent, SessionStatus } from '@/lib/api/types';

import { buildLargeSession, LARGE_MODEL_NAMES } from '@mocks/fixtures/large-session';
import { buildQ3Scenario, MODEL_NAMES, Q3_FIXED_START, THREAD_ANALYST } from '@mocks/fixtures/q3-session';

import { SessionTimeline } from './session-timeline';

const scenario = buildQ3Scenario(Q3_FIXED_START);
const approved = scenario.afterResponse(true).flatMap((step) => ('event' in step ? [step.event] : []));

/** 子线程仍在运行的时刻：数据分析刚开始执行脚本。 */
const subthreadsRunning = scenario.turnOne.slice(
  0,
  scenario.turnOne.findIndex(
    (e) => e.thread_id === THREAD_ANALYST && e.type === 'agent.tool_use' && e.data.name === 'bash',
  ) + 1,
);

function Frame({ events, status }: { events: readonly SessionEvent[]; status: SessionStatus }) {
  const [selected, setSelected] = useState<string | null>(null);
  return (
    <div className="h-screen bg-surface">
      <SessionTimeline
        className="h-full"
        events={events}
        status={status}
        modelNames={MODEL_NAMES}
        selectedId={selected}
        onSelect={(e) => setSelected(e.id)}
        onRespond={() => undefined}
      />
    </div>
  );
}

/** 每 900ms 追加一个事件，演示新事件依次落位与"此刻"下移。 */
function LiveAppend() {
  const queue = useMemo(() => [...scenario.turnTwo], []);
  const [events, setEvents] = useState<readonly SessionEvent[]>(scenario.turnOne);
  useEffect(() => {
    let i = 0;
    const timer = window.setInterval(() => {
      const next = queue[i];
      i += 1;
      if (!next) {
        window.clearInterval(timer);
        return;
      }
      setEvents((current) => [...current, { ...next, created_at: new Date().toISOString() }]);
    }, 900);
    return () => window.clearInterval(timer);
  }, [queue]);
  const last = events.at(-1);
  const status: SessionStatus =
    last?.type === 'session.status_requires_action'
      ? 'requires_action'
      : last?.type === 'session.status_idle'
        ? 'idle'
        : 'running';
  return <Frame events={events} status={status} />;
}

const meta = {
  title: '领域组件/SessionTimeline',
  component: SessionTimeline,
  parameters: { layout: 'fullscreen' },
} satisfies Meta<typeof SessionTimeline>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Finished: Story = {
  name: '已结束',
  args: { events: [], status: 'idle' },
  render: () => <Frame events={[...scenario.turnOne, ...scenario.turnTwo, ...approved]} status="idle" />,
};

export const AwaitingAction: Story = {
  name: '等待处理',
  args: { events: [], status: 'requires_action' },
  render: () => <Frame events={[...scenario.turnOne, ...scenario.turnTwo]} status="requires_action" />,
};

export const SubthreadsRunning: Story = {
  name: '子线程运行中',
  args: { events: [], status: 'running' },
  render: () => <Frame events={subthreadsRunning} status="running" />,
};

export const Live: Story = {
  name: '实时追加',
  args: { events: [], status: 'running' },
  render: () => <LiveAppend />,
};

const large = buildLargeSession();

export const TenThousandEvents: Story = {
  name: '一万条事件',
  args: { events: [], status: 'idle' },
  render: () => (
    <div className="h-screen bg-surface">
      <SessionTimeline className="h-full" events={large} status="idle" modelNames={LARGE_MODEL_NAMES} />
    </div>
  ),
};
