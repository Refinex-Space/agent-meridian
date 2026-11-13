import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { Button } from '@/components/ui/button';
import type { EventOf } from '@/lib/api/types';

import { ToolCallView } from './tool-call-view';

const USE: EventOf<'agent.tool_use'> = {
  id: 'evt_use',
  seq: 10,
  session_id: 'ses_1',
  thread_id: 'thr_1',
  turn_id: 'trn_1',
  type: 'agent.tool_use',
  created_at: '2026-11-13T02:02:14.000Z',
  data: { tool_use_id: 'tu_1', name: 'bash', input: { command: 'python export.py --quarter 2026Q3' } },
};

function resultOf(over: Partial<EventOf<'agent.tool_result'>['data']> = {}): EventOf<'agent.tool_result'> {
  return {
    id: 'evt_result',
    seq: 11,
    session_id: 'ses_1',
    thread_id: 'thr_1',
    turn_id: 'trn_1',
    type: 'agent.tool_result',
    created_at: '2026-11-13T02:02:15.600Z',
    data: {
      tool_use_id: 'tu_1',
      name: 'bash',
      state: 'success',
      permission: 'approved',
      content: [{ type: 'text', text: 'wrote 512 rows to /workspace/exports/q3.csv' }],
      content_ref: null,
      ...over,
    },
  };
}

describe('ToolCallView', () => {
  it('写明工具名、状态、耗时、权限结论，入参与结果都可见', () => {
    render(<ToolCallView use={USE} result={resultOf()} />);
    expect(screen.getByText('bash')).toBeInTheDocument();
    expect(screen.getByText('成功')).toBeInTheDocument();
    expect(screen.getByText('耗时 1.6 秒')).toBeInTheDocument();
    expect(screen.getByText('人工批准')).toBeInTheDocument();
    const input = screen.getByRole('heading', { name: '入参' }).parentElement as HTMLElement;
    expect(input).toHaveTextContent('python export.py --quarter 2026Q3');
    expect(screen.getByRole('heading', { name: '结果' }).parentElement).toHaveTextContent('wrote 512 rows');
  });

  it('机器文本用等宽显示；给人读的结果用正文字体', () => {
    const { rerender } = render(<ToolCallView use={USE} result={resultOf()} />);
    expect(screen.getByText('wrote 512 rows to /workspace/exports/q3.csv').closest('pre')).not.toBeNull();
    const search = { ...USE, data: { ...USE.data, name: 'knowledge_search', input: { query: '三季度' } } };
    rerender(
      <ToolCallView
        use={search}
        result={resultOf({ name: 'knowledge_search', content: [{ type: 'text', text: '命中 8 条。' }] })}
      />,
    );
    const prose = screen.getByText('命中 8 条。');
    expect(prose.tagName).toBe('P');
    expect(prose).not.toHaveClass('font-mono');
  });

  it('被拒绝的结果用信号红写出权限结论', () => {
    render(
      <ToolCallView use={USE} result={resultOf({ state: 'denied', permission: 'denied', content: [] })} />,
    );
    expect(screen.getByText('被拒绝', { selector: '[data-slot=status-badge]' })).toBeInTheDocument();
    expect(screen.getByText('被拒绝', { selector: 'span.text-danger' })).toBeInTheDocument();
  });

  it('等待批准：显示调用方给出的操作，没有结果区', () => {
    const onApprove = vi.fn();
    render(
      <ToolCallView
        use={USE}
        awaiting
        actions={
          <Button size="sm" variant="primary" onClick={onApprove}>
            批准
          </Button>
        }
      />,
    );
    expect(screen.getByText('等待批准')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '批准' }));
    expect(onApprove).toHaveBeenCalledOnce();
    expect(screen.queryByRole('heading', { name: '结果' })).toBeNull();
  });

  it('还没有结果时显示执行中', () => {
    render(<ToolCallView use={USE} />);
    expect(screen.getByText('执行中')).toBeInTheDocument();
    expect(screen.getByText('执行中，结果出来后显示在这里。')).toBeInTheDocument();
  });

  it('结果在对象存储中：写明大小与类型，按需加载', () => {
    const onLoad = vi.fn();
    render(
      <ToolCallView
        use={USE}
        result={resultOf({
          content: [],
          content_ref: { key: 'k', content_type: 'text/csv', size: 3_250_000 },
        })}
        onLoadContent={onLoad}
      />,
    );
    const section = screen.getByRole('heading', { name: '结果' }).parentElement as HTMLElement;
    expect(section).toHaveTextContent('结果较大（3.1 MB，text/csv），保存在对象存储');
    fireEvent.click(within(section).getByRole('button', { name: '加载结果' }));
    expect(onLoad).toHaveBeenCalledOnce();
  });

  it('MCP 工具名写成"服务/工具"', () => {
    const mcp: EventOf<'agent.mcp_tool_use'> = {
      ...USE,
      type: 'agent.mcp_tool_use',
      data: {
        tool_use_id: 'tu_2',
        name: 'mcp__crm__lookup_customer',
        server_name: 'crm',
        input: { id: 'c_1' },
      },
    };
    render(<ToolCallView use={mcp} />);
    expect(screen.getByText('crm/lookup_customer')).toBeInTheDocument();
  });
});
