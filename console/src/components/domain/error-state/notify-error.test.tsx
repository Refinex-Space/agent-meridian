import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';

import { notify, Toaster } from '@/components/ui/sonner';
import { TooltipProvider } from '@/components/ui/tooltip';
import { ApiError } from '@/lib/api/errors';
import { mockClipboard } from '@/test/clipboard';

import { notifyError } from './notify-error';

const conflict = new ApiError(409, {
  type: 'urn:meridian:error:agent.version_published',
  title: '版本已发布',
  status: 409,
  detail: '版本 v12 已经发布，不能再修改。',
  instance: '/admin/v1/tenants/ten_1/projects/prj_1/agents/agt_1/versions/12',
  code: 'agent.version_published',
  request_id: 'req_01JC8Z4V7Q9X',
});

function renderToaster() {
  render(
    <TooltipProvider>
      <Toaster />
    </TooltipProvider>,
  );
}

describe('notifyError：problem+json 的错误通知（T21-A04）', () => {
  afterEach(() => {
    act(() => {
      notify.dismiss();
    });
  });

  it('写明发生了什么、原因与下一步，附可复制的请求 ID，不自动消失', async () => {
    const writeText = mockClipboard();
    renderToaster();
    act(() => {
      notifyError(conflict, '发布失败');
    });
    const toast = (await screen.findByText('发布失败')).closest('li')!;
    expect(toast).toHaveTextContent('版本 v12 已经发布，不能再修改。');
    expect(toast).toHaveTextContent('刷新后再试。');
    const copy = within(toast).getByRole('button', { name: /复制请求 ID/ });
    expect(copy).toHaveTextContent('req_01JC8Z4V7Q9X');
    await userEvent.click(copy);
    expect(writeText).toHaveBeenCalledWith('req_01JC8Z4V7Q9X');
    expect(within(toast).getByRole('button', { name: /close/i })).toBeInTheDocument();
  });

  it('没有给标题时按 HTTP 状态说明；网络错误没有请求 ID', async () => {
    renderToaster();
    act(() => {
      notifyError(new TypeError('Failed to fetch'));
    });
    const toast = (await screen.findByText('无法连接到服务')).closest('li')!;
    expect(toast).toHaveTextContent('检查网络连接后重试。');
    expect(within(toast).queryByRole('button', { name: /复制请求 ID/ })).toBeNull();
  });
});
