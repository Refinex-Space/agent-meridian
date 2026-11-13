import type { SessionStatus } from '@/lib/api/types';
import { cn } from '@/lib/cn';

/**
 * 状态符号（frontend-design.md「设计定稿·状态符号」）：形状加颜色，形状本身就能区分状态。
 * 时间轴节点、线程列表、会话头部用同一套形状。
 */
export type StatusKind = SessionStatus | 'success' | 'failure';

export const SESSION_STATUS_LABEL: Record<SessionStatus, string> = {
  idle: '空闲',
  running: '运行中',
  requires_action: '等待处理',
  rescheduled: '重新调度',
  terminated: '已终止',
};

const TONE: Record<StatusKind, string> = {
  running: 'text-accent',
  requires_action: 'text-brass',
  idle: 'text-ink-2',
  rescheduled: 'text-reschedule',
  terminated: 'text-terminated',
  success: 'text-success',
  failure: 'text-danger',
};

/** 以 (0,0) 为中心、半径约 4 的状态形状，供 SVG 内部复用。 */
export function StatusShape({ kind }: { kind: StatusKind }) {
  switch (kind) {
    case 'running':
      return <circle r="4" fill="currentColor" />;
    case 'requires_action':
      return <path d="M0 -5 5 0 0 5 -5 0z" fill="currentColor" />;
    case 'idle':
      return <circle r="3.5" fill="none" stroke="currentColor" strokeWidth="1.5" />;
    case 'rescheduled':
      return <circle r="3.75" fill="none" stroke="currentColor" strokeWidth="1.5" strokeDasharray="2 1.8" />;
    case 'terminated':
      return <rect x="-3.5" y="-3.5" width="7" height="7" fill="currentColor" />;
    case 'success':
      return (
        <path
          d="M-3.75 0.25 -1.25 2.75 3.75 -2.75"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.6"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      );
    case 'failure':
      return (
        <path
          d="M-3 -3 3 3M3 -3 -3 3"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.6"
          strokeLinecap="round"
        />
      );
  }
}

export interface StatusGlyphProps {
  kind: StatusKind;
  className?: string;
}

/** 12px 的状态符号，放在状态文字前；对读屏隐藏，状态由旁边的文字表达。 */
export function StatusGlyph({ kind, className }: StatusGlyphProps) {
  return (
    <svg viewBox="-6 -6 12 12" aria-hidden className={cn('size-3 shrink-0', TONE[kind], className)}>
      <StatusShape kind={kind} />
    </svg>
  );
}
