import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { TooltipProvider } from '@/components/ui/tooltip';
import { ApiError } from '@/lib/api/errors';
import type { Problem } from '@/lib/api/types';

import { describeError } from './describe-error';
import { ErrorState } from './error-state';

const problem = (over: Partial<Problem> = {}): Problem => ({
  type: 'urn:meridian:error:session.not_found',
  title: 'Not Found',
  status: 404,
  detail: '会话 ses_01JC 不存在。',
  instance: '/v1/sessions/ses_01JC',
  code: 'session.not_found',
  request_id: 'req_01JC9A7M2K',
  ...over,
});

describe('describeError', () => {
  it('按 HTTP 状态给出发生了什么与下一步，原因取 problem 的 detail', () => {
    const d = describeError(new ApiError(404, problem()));
    expect(d).toMatchObject({
      title: '要找的对象不存在',
      detail: '会话 ses_01JC 不存在。',
      requestId: 'req_01JC9A7M2K',
      status: 404,
    });
    expect(describeError(new ApiError(503, null)).title).toBe('服务暂时不可用');
    expect(describeError(problem({ status: 403 })).next).toBe('请联系项目管理员授予所需的角色。');
  });

  it('fetch 抛出的 TypeError 视为网络不可达', () => {
    const d = describeError(new TypeError('Failed to fetch'));
    expect(d).toMatchObject({ title: '无法连接到服务', status: null, detail: null, requestId: null });
  });
});

describe('ErrorState', () => {
  function renderState(props: Partial<Parameters<typeof ErrorState>[0]> = {}) {
    return render(
      <TooltipProvider>
        <ErrorState error={new ApiError(404, problem())} {...props} />
      </TooltipProvider>,
    );
  }

  it('写明发生了什么、原因、下一步与可复制的请求 ID', () => {
    renderState({ title: '无法打开这个会话' });
    const alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent('无法打开这个会话');
    expect(alert).toHaveTextContent('会话 ses_01JC 不存在。');
    expect(alert).toHaveTextContent('它可能已被删除，返回列表查看。');
    expect(screen.getByRole('button', { name: '复制请求 ID req_01JC9A7M2K' })).toBeInTheDocument();
  });

  it('列出字段校验失败的明细', () => {
    renderState({
      error: new ApiError(
        422,
        problem({
          status: 422,
          code: 'request.validation_failed',
          detail: null,
          errors: [{ field: 'name', message: '不能为空' }],
        }),
      ),
    });
    expect(screen.getByText('name').closest('li')).toHaveTextContent('name 不能为空');
  });

  it('给了 onRetry 才显示重试，重试中禁止重复点击', () => {
    const onRetry = vi.fn();
    const { rerender } = renderState();
    expect(screen.queryByRole('button', { name: '重试' })).toBeNull();
    rerender(
      <TooltipProvider>
        <ErrorState error={new ApiError(500, null)} onRetry={onRetry} />
      </TooltipProvider>,
    );
    fireEvent.click(screen.getByRole('button', { name: '重试' }));
    expect(onRetry).toHaveBeenCalledOnce();
    rerender(
      <TooltipProvider>
        <ErrorState error={new ApiError(500, null)} onRetry={onRetry} retrying />
      </TooltipProvider>,
    );
    expect(screen.getByRole('button', { name: '重试' })).toBeDisabled();
  });
});
