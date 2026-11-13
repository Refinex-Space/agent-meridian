import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import type { EventOf, SessionEvent } from '@/lib/api/types';

import { buildQ3Scenario, MODEL_NAMES, Q3_FIXED_START } from '@mocks/fixtures/q3-session';

import { EventCard } from './event-card';

const scenario = buildQ3Scenario(Q3_FIXED_START);
const events: SessionEvent[] = [...scenario.turnOne, ...scenario.turnTwo];
const isResult = (e: SessionEvent): e is EventOf<'agent.tool_result'> => e.type === 'agent.tool_result';
const result = events.find(
  (e) => isResult(e) && e.data.name === 'knowledge_search',
) as EventOf<'agent.tool_result'>;
const before = events.slice(0, events.indexOf(result));

describe('EventCard', () => {
  it('与时间轴同一套文案：工具结果写明工具名与执行耗时，时间显示为时刻', () => {
    render(<EventCard event={result} context={before} modelNames={MODEL_NAMES} />);
    expect(screen.getByText('结果')).toBeInTheDocument();
    expect(screen.getByText('knowledge_search')).toHaveClass('font-mono');
    expect(screen.getByTitle('工具执行耗时')).toBeInTheDocument();
    expect(screen.getByText('10:02:13').tagName).toBe('TIME');
  });

  it('没有上下文时按事件自身描述，不写耗时', () => {
    render(<EventCard event={result} />);
    expect(screen.getByText('knowledge_search')).toBeInTheDocument();
    expect(screen.queryByTitle('工具执行耗时')).toBeNull();
  });

  it('不单独成行的事件退回事件类型', () => {
    const usage = events.find((e) => e.type === 'session.usage') as SessionEvent;
    render(<EventCard event={usage} />);
    expect(screen.getByText('session.usage')).toBeInTheDocument();
  });

  it('可选择时是按钮，选中状态用 aria-pressed 表达；子线程写出线程名', () => {
    const onSelect = vi.fn();
    render(
      <EventCard event={result} context={before} onSelect={onSelect} selected threadName="data-analyst" />,
    );
    const card = screen.getByRole('button');
    expect(card).toHaveAttribute('aria-pressed', 'true');
    expect(card).toHaveTextContent('子线程 data-analyst');
    fireEvent.click(card);
    expect(onSelect).toHaveBeenCalledOnce();
  });
});
