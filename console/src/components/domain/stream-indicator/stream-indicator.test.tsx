import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { StreamIndicator } from './stream-indicator';

describe('StreamIndicator', () => {
  it.each([
    ['open', '已连接'],
    ['connecting', '连接中'],
    ['reconnecting', '重连中'],
    ['closed', '已断开'],
  ] as const)('%s 显示"%s"，并经 status 告知读屏', (state, label) => {
    render(<StreamIndicator state={state} />);
    expect(screen.getByRole('status')).toHaveTextContent(`实时推送${label}`);
  });

  it('只有已断开且给了处理函数时才显示重新连接', () => {
    const onReconnect = vi.fn();
    const { rerender } = render(<StreamIndicator state="reconnecting" onReconnect={onReconnect} />);
    expect(screen.queryByRole('button')).toBeNull();
    rerender(<StreamIndicator state="closed" onReconnect={onReconnect} />);
    fireEvent.click(screen.getByRole('button', { name: '重新连接' }));
    expect(onReconnect).toHaveBeenCalledOnce();
  });
});
