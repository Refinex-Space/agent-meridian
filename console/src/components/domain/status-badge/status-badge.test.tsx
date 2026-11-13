import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import type { Schemas } from '@/lib/api/types';

import { actionStatus, connectivityStatus, toolStatus, turnStatus } from './labels';
import { StatusBadge } from './status-badge';

const conclusion = (
  over: Partial<Schemas['PendingActionConclusion']>,
): Schemas['PendingActionConclusion'] => ({
  origin: 'caller',
  approved: null,
  deny_reason: null,
  remember_scope: null,
  remember_rule: null,
  decision: null,
  error: null,
  ...over,
});

describe('StatusBadge', () => {
  it('会话状态显示符号与文字，语义写在 data-kind 上', () => {
    render(<StatusBadge of="session" status="running" />);
    const badge = screen.getByText('运行中');
    expect(badge).toHaveAttribute('data-kind', 'running');
    expect(badge.querySelector('svg')).toHaveAttribute('aria-hidden');
  });

  it('等待处理用黄铜色文字，其余用墨色', () => {
    const { rerender } = render(<StatusBadge of="session" status="requires_action" />);
    expect(screen.getByText('等待处理')).toHaveClass('text-brass-ink');
    rerender(<StatusBadge of="session" status="idle" />);
    expect(screen.getByText('空闲')).toHaveClass('text-ink');
  });

  it('自定义对象用调用方给出的语义与文字', () => {
    render(<StatusBadge of="custom" kind="failure" label="解析失败" size="sm" />);
    const badge = screen.getByText('解析失败');
    expect(badge).toHaveAttribute('data-kind', 'failure');
    expect(badge).toHaveClass('text-12');
  });
});

describe('状态映射', () => {
  it('轮次：正常结束为空闲，被中断为已终止，其余停止原因为失败', () => {
    expect(turnStatus('running')).toEqual({ kind: 'running', label: '运行中' });
    expect(turnStatus('end_turn')).toEqual({ kind: 'idle', label: '正常结束' });
    expect(turnStatus('interrupted').kind).toBe('terminated');
    expect(turnStatus('budget_exceeded')).toEqual({ kind: 'failure', label: '预算耗尽' });
  });

  it('工具调用：等待批准用等待处理的语义，被拒绝与出错都是失败', () => {
    expect(toolStatus('awaiting')).toEqual({ kind: 'requires_action', label: '等待批准' });
    expect(toolStatus('denied')).toEqual({ kind: 'failure', label: '被拒绝' });
    expect(toolStatus('error').kind).toBe('failure');
    expect(toolStatus('interrupted').kind).toBe('terminated');
  });

  it('待处理动作：按结论写明批准、拒绝的来源、恢复决定与自定义工具结果', () => {
    expect(actionStatus('pending')).toEqual({ kind: 'requires_action', label: '等待回应' });
    expect(actionStatus('expired')).toEqual({ kind: 'terminated', label: '已失效' });
    expect(actionStatus('resolved', conclusion({ approved: true }))).toEqual({
      kind: 'success',
      label: '已批准',
    });
    expect(actionStatus('resolved', conclusion({ approved: false })).label).toBe('已拒绝');
    // 超时：runtime 写 status=expired 并带 origin=timeout 的结论，按结论显示。
    expect(actionStatus('expired', conclusion({ approved: false, origin: 'timeout' }))).toEqual({
      kind: 'failure',
      label: '超时视为拒绝',
    });
    expect(actionStatus('resolved', conclusion({ approved: false, origin: 'unattended' })).label).toBe(
      '无人值守自动拒绝',
    );
    expect(actionStatus('resolved', conclusion({ decision: 'skip' }))).toEqual({
      kind: 'idle',
      label: '跳过',
    });
    expect(actionStatus('resolved', conclusion({ error: true })).kind).toBe('failure');
  });
});

describe('connectivityStatus', () => {
  const check = (status: 'ok' | 'auth_failed' | 'unreachable' | 'error') => ({
    status,
    http_status: null,
    latency_ms: 120,
    message: null,
    checked_at: '2026-11-13T02:00:00Z',
  });

  it('从未测试为空闲，连通为成功，其余按原因写明并标为失败', () => {
    expect(connectivityStatus(null)).toEqual({ kind: 'idle', label: '未测试' });
    expect(connectivityStatus(check('ok'))).toEqual({ kind: 'success', label: '连通' });
    expect(connectivityStatus(check('auth_failed'))).toEqual({ kind: 'failure', label: '凭据无效' });
    expect(connectivityStatus(check('unreachable'))).toEqual({ kind: 'failure', label: '连不上' });
    expect(connectivityStatus(check('error'))).toEqual({ kind: 'failure', label: '出错' });
  });
});
