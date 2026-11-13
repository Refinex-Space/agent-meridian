import type { Meta, StoryObj } from '@storybook/react-vite';

import type { EventOf } from '@/lib/api/types';
import { focus, hover, storyFrame } from '@/test/stories';

import { buildQ3Scenario, Q3_FIXED_START } from '@mocks/fixtures/q3-session';

import { KeyValueList } from '../key-value-list';
import { TokenUsage } from '../token-usage';
import { ToolCallView } from '../tool-call-view';

import { InspectorPanel, InspectorSection } from './inspector-panel';

const scenario = buildQ3Scenario(Q3_FIXED_START);
const events = [...scenario.turnOne, ...scenario.turnTwo];
const use = events.find(
  (e) => e.type === 'agent.tool_use' && e.data.name === 'knowledge_search',
) as EventOf<'agent.tool_use'>;
const result = events.find(
  (e) => e.type === 'agent.tool_result' && e.data.tool_use_id === use.data.tool_use_id,
) as EventOf<'agent.tool_result'>;

function Summary() {
  return (
    <>
      <KeyValueList
        items={[
          { label: '时间', value: '2026-11-13 10:02:13.204 UTC+08:00' },
          { label: '线程', value: '主线程' },
          { label: '轮次', value: '第 1 轮' },
        ]}
        labelWidth="narrow"
      />
      <ToolCallView use={use} result={result} />
      <InspectorSection title="本轮用量">
        <TokenUsage
          variant="detail"
          usage={{
            input_tokens: 12_480,
            output_tokens: 1_204,
            cached_tokens: 8_000,
            cache_creation_tokens: 0,
            reasoning_tokens: 0,
          }}
        />
      </InspectorSection>
    </>
  );
}

/** 右侧详情面板：概要与原始 JSON 两页，可收起；宽度由工作台布局决定。 */
const meta = {
  title: '领域组件/InspectorPanel',
  component: InspectorPanel,
  decorators: [storyFrame('h-[calc(100vh-4rem)] w-86 border border-rule', 'p-0 bg-canvas')],
  args: { title: '调用 knowledge_search', raw: result, onCollapse: () => undefined, children: <Summary /> },
} satisfies Meta<typeof InspectorPanel>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: '默认' };

export const Hover: Story = { name: '悬停', parameters: hover('[data-slot=button]') };

export const Focus: Story = { name: '聚焦', parameters: focus('[role=tab]') };

export const Empty: Story = { name: '空', args: { title: undefined, raw: undefined, children: undefined } };

export const Loading: Story = { name: '加载', args: { loading: true } };

export const LongContent: Story = {
  name: '长内容',
  args: {
    title: '调用 mcp crm/lookup_customer_orders_with_full_history_and_invoices',
  },
};
