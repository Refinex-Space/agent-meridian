import { fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import type { SessionEvent } from '@/lib/api/types';

import { ACTION_EXPORT, buildQ3Scenario, MODEL_NAMES, Q3_FIXED_START } from '@mocks/fixtures/q3-session';

import { SessionTimeline } from './session-timeline';

const scenario = buildQ3Scenario(Q3_FIXED_START);
const history: SessionEvent[] = [...scenario.turnOne, ...scenario.turnTwo];

// jsdom 不做布局：虚拟滚动按 clientHeight 取视口、按 offsetHeight 量行高。给 4000px 的视口、40px 的行，全部行都会渲染。
beforeEach(() => {
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(4000);
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(1000);
  vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockReturnValue(40);
});

describe('SessionTimeline', () => {
  it('按事件渲染行：轮次主刻度、间隔、模型显示名，等待处理时有"此刻"', () => {
    render(
      <SessionTimeline
        events={history}
        status="requires_action"
        modelNames={MODEL_NAMES}
        now={() => Date.now()}
      />,
    );
    const list = screen.getByRole('list', { name: '事件时间线' });
    expect(within(list).getByText('第 1 轮')).toBeInTheDocument();
    expect(within(list).getByText('第 2 轮')).toBeInTheDocument();
    expect(within(list).getByText('间隔 4 分 12 秒')).toBeInTheDocument();
    expect(within(list).getAllByText('qwen-plus').length).toBeGreaterThan(0);
    expect(within(list).getByText('此刻')).toBeInTheDocument();
    expect(within(list).getByText('等待批准', { selector: 'span' })).toBeInTheDocument();
  });

  it('会话空闲时没有"此刻"', () => {
    const approved = scenario.afterResponse(true).flatMap((s) => ('event' in s ? [s.event] : []));
    render(<SessionTimeline events={[...history, ...approved]} status="idle" />);
    expect(screen.queryByText('此刻')).not.toBeInTheDocument();
  });

  it('行内批准与拒绝回传动作 ID；没有回应能力时不显示按钮', () => {
    const onRespond = vi.fn();
    const { rerender } = render(
      <SessionTimeline events={history} status="requires_action" onRespond={onRespond} />,
    );
    fireEvent.click(screen.getByRole('button', { name: '批准' }));
    fireEvent.click(screen.getByRole('button', { name: '拒绝' }));
    expect(onRespond.mock.calls).toEqual([
      [ACTION_EXPORT, true],
      [ACTION_EXPORT, false],
    ]);
    rerender(<SessionTimeline events={history} status="requires_action" />);
    expect(screen.queryByRole('button', { name: '批准' })).not.toBeInTheDocument();
  });

  it('提交回应期间，对应按钮显示进度，其他按钮禁用', () => {
    render(
      <SessionTimeline
        events={history}
        status="requires_action"
        onRespond={() => undefined}
        respondingActionId={ACTION_EXPORT}
      />,
    );
    expect(screen.getByRole('button', { name: '批准' })).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByRole('button', { name: '拒绝' })).toBeDisabled();
  });

  it('点击行选中事件；方向键在行之间移动选中，跳过间隔行', () => {
    const onSelect = vi.fn();
    const userMessage = history.find((e) => e.type === 'user.message' && e.seq > scenario.turnOne.length);
    const idle = scenario.turnOne.at(-1)!;
    const { rerender } = render(
      <SessionTimeline events={history} status="idle" onSelect={onSelect} selectedId={idle.id} />,
    );
    const current = screen.getAllByRole('button').find((b) => b.getAttribute('aria-current') === 'true');
    expect(current).toBeDefined();
    fireEvent.keyDown(current!, { key: 'ArrowDown' });
    expect(onSelect).toHaveBeenLastCalledWith(userMessage);

    rerender(
      <SessionTimeline events={history} status="idle" onSelect={onSelect} selectedId={userMessage!.id} />,
    );
    const next = screen.getAllByRole('button').find((b) => b.getAttribute('aria-current') === 'true');
    fireEvent.keyDown(next!, { key: 'ArrowUp' });
    expect(onSelect).toHaveBeenLastCalledWith(idle);
    fireEvent.keyDown(next!, { key: 'Home' });
    expect(onSelect).toHaveBeenLastCalledWith(history[0]);
  });
});
