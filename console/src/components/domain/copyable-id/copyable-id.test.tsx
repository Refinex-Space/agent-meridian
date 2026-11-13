import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { TooltipProvider } from '@/components/ui/tooltip';
import { mockClipboard } from '@/test/clipboard';

import { CopyableId } from './copyable-id';

const ID = 'ses_01JC8Z4V7QX3M9N2P8R5';

function renderId(props: Partial<Parameters<typeof CopyableId>[0]> = {}) {
  return render(
    <TooltipProvider>
      <CopyableId id={ID} label="会话 ID" {...props} />
    </TooltipProvider>,
  );
}

describe('CopyableId', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('中间省略，可访问名称包含"复制会话 ID"与可见文字', () => {
    renderId();
    expect(screen.getByRole('button', { name: '复制会话 ID ses_01JC8Z…N2P8R5' })).toBeInTheDocument();
  });

  it('full 时显示完整 ID', () => {
    renderId({ full: true });
    expect(screen.getByRole('button')).toHaveTextContent(ID);
  });

  it('点击复制完整 ID，图标变为对勾并告知读屏，1.5 秒后复原', async () => {
    const writeText = mockClipboard();
    renderId();
    const button = screen.getByRole('button');
    fireEvent.click(button);
    await act(async () => {
      await Promise.resolve();
    });
    expect(writeText).toHaveBeenCalledWith(ID);
    expect(button).toHaveAttribute('data-state-copy', 'copied');
    expect(screen.getByRole('status')).toHaveTextContent('已复制会话 ID');
    act(() => {
      vi.advanceTimersByTime(1500);
    });
    expect(button).toHaveAttribute('data-state-copy', 'idle');
    expect(screen.getByRole('status')).toHaveTextContent('');
  });

  it('剪贴板不可用时提示复制失败', async () => {
    mockClipboard({ available: false });
    renderId();
    fireEvent.click(screen.getByRole('button'));
    await act(async () => {
      await Promise.resolve();
    });
    expect(screen.getByRole('button')).toHaveAttribute('data-state-copy', 'failed');
    expect(screen.getByRole('status')).toHaveTextContent('复制失败');
  });
});
