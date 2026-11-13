import type { Meta, StoryObj } from '@storybook/react-vite';

import { Button } from '@/components/ui/button';
import type { EventOf } from '@/lib/api/types';
import { focus, hover, storyFrame } from '@/test/stories';

import { ToolCallView } from './tool-call-view';

const USE: EventOf<'agent.tool_use'> = {
  id: 'evt_01JCQ9C6W2Y5A8D1F4H7K0M3PA',
  seq: 61,
  session_id: 'ses_01JCQ8M3T7V5X2K9P4R6W1Y8ZD',
  thread_id: 'thr_01JCQ8M3TA2N7R4V9X1K6P3W5Z',
  turn_id: 'trn_01JCQ9C6W2Y5A8D1F4H7K0M3PB',
  type: 'agent.tool_use',
  created_at: '2026-11-13T02:06:24.120Z',
  data: {
    tool_use_id: 'tu_01JCQ9C6W2Y5A8D1F4H7K0M3PC',
    name: 'bash',
    input: { command: 'python export.py --category 下滑品类 --quarter 2026Q3 --format csv', timeout: 600 },
  },
};

function result(over: Partial<EventOf<'agent.tool_result'>['data']> = {}): EventOf<'agent.tool_result'> {
  return {
    ...USE,
    id: 'evt_01JCQ9C6W2Y5A8D1F4H7K0M3PD',
    seq: 62,
    type: 'agent.tool_result',
    created_at: '2026-11-13T02:06:25.720Z',
    data: {
      tool_use_id: USE.data.tool_use_id,
      name: 'bash',
      state: 'success',
      permission: 'approved',
      content: [
        { type: 'text', text: 'wrote 512 rows to /workspace/exports/q3-declining.csv\nelapsed 1.42s' },
      ],
      content_ref: null,
      ...over,
    },
  };
}

const LONG_OUTPUT = Array.from(
  { length: 60 },
  (_, i) =>
    `2026-11-13 10:06:${String(i % 60).padStart(2, '0')} INFO  export: batch ${i + 1}/60 wrote 512 rows`,
).join('\n');

/** 工具调用：工具名、状态、耗时与权限结论，入参与结果；等待批准时给出操作，大结果按需加载。 */
const meta = {
  title: '领域组件/ToolCallView',
  component: ToolCallView,
  decorators: [storyFrame('w-160')],
  args: { use: USE, result: result() },
} satisfies Meta<typeof ToolCallView>;

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { name: '成功' };

export const Awaiting: Story = {
  name: '等待批准',
  args: {
    result: null,
    awaiting: true,
    actions: (
      <>
        <Button size="sm" variant="primary">
          批准
        </Button>
        <Button size="sm">拒绝</Button>
      </>
    ),
  },
};

export const Hover: Story = {
  name: '悬停',
  args: Awaiting.args,
  parameters: hover('[data-slot=button]'),
};

export const Focus: Story = {
  name: '聚焦',
  args: Awaiting.args,
  parameters: focus('[data-slot=button]'),
};

export const Running: Story = { name: '执行中', args: { result: null } };

export const Denied: Story = {
  name: '被拒绝',
  args: {
    result: result({
      state: 'denied',
      permission: 'denied',
      content: [{ type: 'text', text: '审批人拒绝了这次调用：导出的明细包含门店负责人的手机号。' }],
    }),
  },
};

export const Error: Story = {
  name: '出错',
  args: {
    result: result({
      state: 'error',
      permission: 'allowed',
      content: [
        {
          type: 'text',
          text: 'Traceback (most recent call last):\n  File "export.py", line 42\nKeyError: \'category\'',
        },
      ],
    }),
  },
};

export const Stored: Story = {
  name: '结果在对象存储',
  args: {
    result: result({
      content: [],
      content_ref: { key: 'blobs/1', content_type: 'text/csv', size: 3_250_000 },
    }),
    onLoadContent: () => undefined,
  },
};

export const LongContent: Story = {
  name: '长内容',
  args: { result: result({ content: [{ type: 'text', text: LONG_OUTPUT }] }) },
};
