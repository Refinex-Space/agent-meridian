import { MutationObserver } from '@tanstack/react-query';
import { act, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { notify, Toaster } from '@/components/ui/sonner';
import { TooltipProvider } from '@/components/ui/tooltip';
import { ApiError } from '@/lib/api/errors';

import { createQueryClient, isRetryable } from './query-client';

const problem = (status: number) =>
  new ApiError(status, {
    type: 'urn:meridian:error:x',
    title: 'x',
    status,
    detail: '缺少必填字段 name。',
    instance: '/admin/v1/x',
    code: 'request.validation_failed',
    request_id: 'req_mutation',
  });

describe('TanStack Query 的统一配置', () => {
  afterEach(() => {
    act(() => {
      notify.dismiss();
    });
  });

  it('只重试网络错误、401（令牌已在续期）、限流与服务端错误', () => {
    expect(isRetryable(new TypeError('Failed to fetch'))).toBe(true);
    expect(isRetryable(problem(401))).toBe(true);
    expect(isRetryable(problem(503))).toBe(true);
    expect(isRetryable(problem(429))).toBe(true);
    expect(isRetryable(problem(404))).toBe(false);
    expect(isRetryable(problem(400))).toBe(false);
  });

  it('变更失败默认发错误通知；meta.notifyError 为 false 时由界面就地显示', async () => {
    render(
      <TooltipProvider>
        <Toaster />
      </TooltipProvider>,
    );
    const client = createQueryClient();
    const fail = () => Promise.reject(problem(400));
    const quiet = new MutationObserver(client, { mutationFn: fail, meta: { notifyError: false } });
    await act(() => quiet.mutate().catch(() => undefined));
    expect(screen.queryByText('请求的内容有误')).toBeNull();

    const loud = new MutationObserver(client, { mutationFn: fail, meta: { errorTitle: '保存失败' } });
    await act(() => loud.mutate().catch(() => undefined));
    expect(await screen.findByText('保存失败')).toBeInTheDocument();
    expect(screen.getByText('缺少必填字段 name。')).toBeInTheDocument();
  });
});
