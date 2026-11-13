import type { Meta, StoryObj } from '@storybook/react-vite';

import type { EventOf, SessionEvent } from '@/lib/api/types';
import { focus, hover, storyFrame } from '@/test/stories';

import { buildQ3Scenario, MODEL_NAMES, Q3_FIXED_START } from '@mocks/fixtures/q3-session';

import { EventCard } from './event-card';

const scenario = buildQ3Scenario(Q3_FIXED_START);
const EVENTS: SessionEvent[] = [...scenario.turnOne, ...scenario.turnTwo];
const find = <T extends SessionEvent['type']>(type: T, name?: string) =>
  EVENTS.find(
    (e) => e.type === type && (name === undefined || ('name' in e.data && e.data.name === name)),
  ) as EventOf<T>;
const before = (event: SessionEvent) => EVENTS.slice(0, EVENTS.indexOf(event));

const RESULT = find('agent.tool_result', 'knowledge_search');
const PENDING = find('session.status_requires_action');
const REPLY = find('agent.message');
const MODEL = find('span.model_request_end');
const USE = find('agent.tool_use', 'bash');

/** 单个事件的摘要：与时间轴同一套节点符号与文案，用在待处理动作、搜索结果等离开时间轴的地方。 */
const meta = {
  title: '领域组件/EventCard',
  component: EventCard,
  decorators: [storyFrame('w-160')],
  args: { event: RESULT, context: before(RESULT), modelNames: MODEL_NAMES },
} satisfies Meta<typeof EventCard>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {
  name: '默认',
  render: () => (
    <div className="flex flex-col gap-2">
      {[USE, RESULT, MODEL, REPLY, PENDING].map((event) => (
        <EventCard key={event.id} event={event} context={before(event)} modelNames={MODEL_NAMES} />
      ))}
    </div>
  ),
};

export const Selectable: Story = { name: '可选择', args: { onSelect: () => undefined } };

export const Hover: Story = {
  name: '悬停',
  args: { onSelect: () => undefined },
  parameters: hover('[data-slot=event-card]'),
};

export const Focus: Story = {
  name: '聚焦',
  args: { onSelect: () => undefined },
  parameters: focus('[data-slot=event-card]'),
};

export const Selected: Story = { name: '选中', args: { onSelect: () => undefined, selected: true } };

export const Subthread: Story = { name: '子线程', args: { threadName: 'data-analyst' } };

export const LongContent: Story = {
  name: '长内容',
  args: { event: REPLY, context: before(REPLY) },
  decorators: [storyFrame('w-120')],
};
