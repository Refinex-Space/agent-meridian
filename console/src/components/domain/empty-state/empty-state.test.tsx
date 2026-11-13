import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { Button } from '@/components/ui/button';

import { EmptyState } from './empty-state';

describe('EmptyState', () => {
  it('首次使用：一句话加主操作，带插画', () => {
    const { container } = render(
      <EmptyState
        variant="first-use"
        title="还没有智能体，创建一个开始调试会话"
        action={<Button variant="primary">创建智能体</Button>}
      />,
    );
    expect(screen.getByRole('status')).toHaveTextContent('还没有智能体');
    expect(screen.getByRole('button', { name: '创建智能体' })).toBeInTheDocument();
    expect(container.querySelector('svg')).toHaveAttribute('aria-hidden');
  });

  it('筛选为空：提供清除筛选', () => {
    const onClear = vi.fn();
    render(<EmptyState variant="filtered" title="没有匹配的会话" onClearFilters={onClear} />);
    fireEvent.click(screen.getByRole('button', { name: '清除筛选' }));
    expect(onClear).toHaveBeenCalledOnce();
  });

  it('行内尺寸不带插画', () => {
    const { container } = render(<EmptyState variant="clear" size="inline" title="没有等待回应的动作" />);
    expect(container.querySelector('svg')).toBeNull();
  });
});
