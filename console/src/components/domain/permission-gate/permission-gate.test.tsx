import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { Button } from '@/components/ui/button';
import { TooltipProvider } from '@/components/ui/tooltip';

import { PermissionGate } from './permission-gate';

const REASON = '需要编辑智能体的权限，请联系项目管理员';

function renderGate(props: { allowed: boolean; mode: 'hide' | 'disable'; onClick?: () => void }) {
  return render(
    <TooltipProvider>
      <PermissionGate allowed={props.allowed} mode={props.mode} reason={REASON}>
        <Button onClick={props.onClick}>发布版本</Button>
      </PermissionGate>
    </TooltipProvider>,
  );
}

describe('PermissionGate', () => {
  it('有权限时原样渲染，点击生效', () => {
    const onClick = vi.fn();
    renderGate({ allowed: true, mode: 'disable', onClick });
    const button = screen.getByRole('button', { name: '发布版本' });
    expect(button).not.toHaveAttribute('aria-disabled');
    fireEvent.click(button);
    expect(onClick).toHaveBeenCalledOnce();
  });

  it('hide：没有权限时不渲染', () => {
    renderGate({ allowed: false, mode: 'hide' });
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('disable：保持可聚焦，标记 aria-disabled，读屏读到原因，点击不触发操作', () => {
    const onClick = vi.fn();
    renderGate({ allowed: false, mode: 'disable', onClick });
    const button = screen.getByRole('button', { name: '发布版本' });
    expect(button).toHaveAttribute('aria-disabled', 'true');
    expect(button).not.toBeDisabled();
    expect(button).toHaveAccessibleDescription(REASON);
    button.focus();
    expect(button).toHaveFocus();
    fireEvent.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });

  it('explain：整块没有权限时用说明代替空白', () => {
    render(<PermissionGate allowed={false} mode="explain" title="没有权限查看凭据" reason={REASON} />);
    expect(screen.getByRole('status')).toHaveTextContent('没有权限查看凭据');
    expect(screen.getByRole('status')).toHaveTextContent(REASON);
  });

  it('explain：有权限时不渲染说明', () => {
    const { container } = render(
      <PermissionGate allowed mode="explain" title="没有权限查看凭据" reason={REASON} />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});
