import { QueryClient, QueryClientProvider, useSuspenseQuery } from '@tanstack/react-query';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { TooltipProvider } from '@/components/ui/tooltip';
import { ApiError } from '@/lib/api/errors';

import { DataRegion } from './data-region';

/** 在 `ms` 毫秒后得到结果（或失败）的查询。 */
function Content({ load }: { load: () => Promise<string> }) {
  const { data } = useSuspenseQuery({ queryKey: ['region'], queryFn: load, retry: false });
  return <p>内容：{data}</p>;
}

/** `ms` 毫秒后调用 `value`：返回值即结果，抛出的错误即失败。 */
function delayed<T>(ms: number, value: () => T): () => Promise<T> {
  return () => new Promise<void>((resolve) => setTimeout(resolve, ms)).then(value);
}

function renderRegion(children: ReactNode) {
  const client = new QueryClient();
  return render(
    <QueryClientProvider client={client}>
      <TooltipProvider>
        <DataRegion skeleton={<div data-testid="skeleton" />} errorTitle="无法加载会话列表">
          {children}
        </DataRegion>
      </TooltipProvider>
    </QueryClientProvider>,
  );
}

describe('DataRegion：区域级的加载与错误', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: false });
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('150ms 内返回的数据不显示骨架', async () => {
    renderRegion(<Content load={delayed(100, () => '会话')} />);
    await act(() => vi.advanceTimersByTimeAsync(100));
    expect(screen.getByText('内容：会话')).toBeInTheDocument();
    expect(screen.queryByTestId('skeleton')).toBeNull();
  });

  it('超过 150ms 才出现骨架，出现后至少保持 300ms', async () => {
    renderRegion(<Content load={delayed(200, () => '会话')} />);
    await act(() => vi.advanceTimersByTimeAsync(149));
    expect(screen.queryByTestId('skeleton')).toBeNull();
    await act(() => vi.advanceTimersByTimeAsync(1));
    expect(screen.getByTestId('skeleton')).toBeInTheDocument();
    // 数据在 200ms 到达，骨架仍要显示到 150 + 300 = 450ms。
    await act(() => vi.advanceTimersByTimeAsync(100));
    expect(screen.getByTestId('skeleton')).toBeInTheDocument();
    expect(screen.queryByText('内容：会话')).toBeNull();
    await act(() => vi.advanceTimersByTimeAsync(200));
    expect(screen.getByText('内容：会话')).toBeInTheDocument();
    expect(screen.queryByTestId('skeleton')).toBeNull();
  });

  it('加载失败时只在区域内显示 problem+json 的错误与请求 ID，重试后恢复', async () => {
    let fail = true;
    const load = delayed(10, () => {
      if (fail) {
        throw new ApiError(503, {
          type: 'urn:meridian:error:service.unavailable',
          title: '服务不可用',
          status: 503,
          detail: 'runtime 暂时不可用。',
          instance: '/v1/sessions',
          code: 'service.unavailable',
          request_id: 'req_01JC8Z',
        });
      }
      return '会话';
    });
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    renderRegion(<Content load={load} />);
    await act(() => vi.advanceTimersByTimeAsync(10));
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('无法加载会话列表');
    expect(alert).toHaveTextContent('runtime 暂时不可用。');
    expect(screen.getByRole('button', { name: /复制请求 ID/ })).toHaveTextContent('req_01JC8Z');

    fail = false;
    vi.useRealTimers();
    await userEvent.click(screen.getByRole('button', { name: '重试' }));
    expect(await screen.findByText('内容：会话')).toBeInTheDocument();
  });
});
