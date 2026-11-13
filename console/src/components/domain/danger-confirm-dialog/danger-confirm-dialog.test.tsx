import { act, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';

import { TooltipProvider } from '@/components/ui/tooltip';
import { ApiError } from '@/lib/api/sessions';

import { DangerConfirmDialog } from './danger-confirm-dialog';

function Harness({
  onConfirm,
  confirmName,
}: {
  onConfirm: () => Promise<void> | void;
  confirmName?: string;
}) {
  const [open, setOpen] = useState(true);
  return (
    <TooltipProvider>
      <span data-testid="open-state">{open ? '打开' : '关闭'}</span>
      <DangerConfirmDialog
        open={open}
        onOpenChange={setOpen}
        title="吊销 API Key 经营分析看板"
        consequences="吊销后，使用这个 Key 的业务系统会立刻收到 401。"
        confirmLabel="吊销 API Key"
        confirmName={confirmName}
        onConfirm={onConfirm}
      />
    </TooltipProvider>
  );
}

describe('DangerConfirmDialog', () => {
  it('写明后果；不要求名称时直接可确认，成功后关闭', async () => {
    const onConfirm = vi.fn().mockResolvedValue(undefined);
    render(<Harness onConfirm={onConfirm} />);
    expect(screen.getByRole('alertdialog')).toHaveAccessibleDescription(
      '吊销后，使用这个 Key 的业务系统会立刻收到 401。',
    );
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '吊销 API Key' }));
      await Promise.resolve();
    });
    expect(onConfirm).toHaveBeenCalledOnce();
    expect(screen.getByTestId('open-state')).toHaveTextContent('关闭');
  });

  it('要求输入名称：名称完全一致前确认按钮不可用', () => {
    render(<Harness onConfirm={vi.fn()} confirmName="经营分析看板" />);
    const confirm = screen.getByRole('button', { name: '吊销 API Key' });
    const input = screen.getByLabelText('输入 经营分析看板 以确认');
    expect(confirm).toBeDisabled();
    fireEvent.change(input, { target: { value: '经营分析' } });
    expect(confirm).toBeDisabled();
    fireEvent.change(input, { target: { value: '经营分析看板 ' } });
    expect(confirm).toBeDisabled();
    fireEvent.change(input, { target: { value: '经营分析看板' } });
    expect(confirm).toBeEnabled();
  });

  it('输入名称后按回车也能确认', async () => {
    const onConfirm = vi.fn().mockResolvedValue(undefined);
    render(<Harness onConfirm={onConfirm} confirmName="经营分析看板" />);
    const input = screen.getByLabelText('输入 经营分析看板 以确认');
    fireEvent.change(input, { target: { value: '经营分析看板' } });
    await act(async () => {
      fireEvent.submit(input.closest('form') as HTMLFormElement);
      await Promise.resolve();
    });
    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it('确认中禁止重复提交与取消；失败时保持打开并显示原因与请求 ID', async () => {
    let reject: (e: unknown) => void = () => undefined;
    const onConfirm = vi.fn(
      () =>
        new Promise<void>((_, r) => {
          reject = r;
        }),
    );
    render(<Harness onConfirm={onConfirm} />);
    const confirm = screen.getByRole('button', { name: '吊销 API Key' });
    await act(async () => {
      fireEvent.click(confirm);
      await Promise.resolve();
    });
    expect(confirm).toBeDisabled();
    expect(confirm).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByRole('button', { name: '取消' })).toBeDisabled();
    fireEvent.click(confirm);
    expect(onConfirm).toHaveBeenCalledOnce();

    await act(async () => {
      reject(
        new ApiError(409, {
          type: 'urn:meridian:error:api_key.already_revoked',
          title: 'Conflict',
          status: 409,
          detail: '这个 Key 已被其他管理员吊销。',
          instance: '/admin/v1/api-keys/key_01',
          code: 'api_key.already_revoked',
          request_id: 'req_01JC9A7M2K',
        }),
      );
      await Promise.resolve();
    });
    expect(screen.getByTestId('open-state')).toHaveTextContent('打开');
    expect(screen.getByRole('alert')).toHaveTextContent('没有完成：吊销 API Key');
    expect(screen.getByRole('alert')).toHaveTextContent('这个 Key 已被其他管理员吊销。');
    expect(screen.getByRole('alert')).toHaveTextContent('req_01JC9A7M2K');
    expect(confirm).toBeEnabled();
  });
});
