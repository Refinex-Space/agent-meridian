import { act, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { TooltipProvider } from '@/components/ui/tooltip';

import { RelativeTime } from './relative-time';

describe('RelativeTime', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('显示相对时间，<time> 带机器可读的时间', () => {
    render(
      <TooltipProvider>
        <RelativeTime value="2026-11-13T01:55:00Z" now={Date.parse('2026-11-13T02:00:00Z')} />
      </TooltipProvider>,
    );
    const time = screen.getByText('5 分钟前');
    expect(time.tagName).toBe('TIME');
    expect(time).toHaveAttribute('dateTime', '2026-11-13T01:55:00Z');
  });

  it('没有传 now 时跟随共享时钟每 30 秒刷新', () => {
    vi.useFakeTimers();
    vi.setSystemTime(Date.parse('2026-11-13T02:00:00Z'));
    render(
      <TooltipProvider>
        <RelativeTime value="2026-11-13T01:59:30Z" />
      </TooltipProvider>,
    );
    expect(screen.getByText('刚刚')).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(30_000);
    });
    expect(screen.getByText('1 分钟前')).toBeInTheDocument();
  });
});
