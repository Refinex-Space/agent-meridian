import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { TooltipProvider } from '@/components/ui/tooltip';

import { ApprovalForm, type ApprovalFormProps } from './approval-form';

const RULES = ['python scripts/export_decline.py', 'python scripts/'];

function renderForm(props: Partial<ApprovalFormProps> = {}) {
  const onDecide = vi.fn();
  render(
    <TooltipProvider>
      <ApprovalForm actionId="act_1" toolName="bash" suggestedRules={RULES} onDecide={onDecide} {...props} />
    </TooltipProvider>,
  );
  return onDecide;
}

describe('ApprovalForm', () => {
  it('默认只这一次：批准时不记住', () => {
    const onDecide = renderForm();
    expect(screen.queryByLabelText('记住的范围')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '批准' }));
    expect(onDecide).toHaveBeenCalledWith({ approved: true, rememberScope: 'none', rememberRule: null });
  });

  it('记住时默认选第一条建议规则，拒绝也带上作用域与规则', () => {
    const onDecide = renderForm();
    fireEvent.click(screen.getByRole('radio', { name: '本项目内不再询问' }));
    expect(screen.getByRole('combobox', { name: '记住的范围' })).toHaveTextContent(
      'bash(python scripts/export_decline.py)',
    );
    fireEvent.click(screen.getByRole('button', { name: '拒绝' }));
    expect(onDecide).toHaveBeenCalledWith({
      approved: false,
      rememberScope: 'project',
      rememberRule: 'python scripts/export_decline.py',
    });
  });

  it('没有建议规则时按原来的方式记', () => {
    const onDecide = renderForm({ toolName: 'web_fetch', suggestedRules: [] });
    fireEvent.click(screen.getByRole('radio', { name: '本会话内不再询问' }));
    expect(screen.getByRole('combobox', { name: '记住的范围' })).toHaveTextContent('整个 web_fetch 工具');
    fireEvent.click(screen.getByRole('button', { name: '批准' }));
    expect(onDecide).toHaveBeenCalledWith({ approved: true, rememberScope: 'session', rememberRule: null });
  });

  it('提交中：两个按钮都不能再点', () => {
    const onDecide = renderForm({ pending: 'approve' });
    expect(screen.getByRole('button', { name: '批准' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '拒绝' }));
    expect(onDecide).not.toHaveBeenCalled();
  });

  it('没有回应权限：按钮可聚焦但不可用，读屏读到原因，选项不可改', () => {
    const onDecide = renderForm({ deniedReason: '需要回应待处理动作的权限，请联系项目管理员' });
    const approve = screen.getByRole('button', { name: '批准' });
    expect(approve).toHaveAttribute('aria-disabled', 'true');
    expect(approve).toHaveAccessibleDescription('需要回应待处理动作的权限，请联系项目管理员');
    fireEvent.click(approve);
    expect(onDecide).not.toHaveBeenCalled();
    expect(screen.getByRole('radio', { name: '只这一次' })).toBeDisabled();
  });
});
