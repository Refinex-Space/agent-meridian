import { Button } from '@/components/ui/button';
import { cn } from '@/lib/cn';
import type { StreamState } from '@/lib/sse/session-stream';

import { StatusGlyph, type StatusKind } from '../status-badge';

const STATE: Record<StreamState, { kind: StatusKind; label: string }> = {
  open: { kind: 'success', label: '已连接' },
  connecting: { kind: 'rescheduled', label: '连接中' },
  reconnecting: { kind: 'rescheduled', label: '重连中' },
  closed: { kind: 'terminated', label: '已断开' },
};

export interface StreamIndicatorProps {
  state: StreamState;
  /** 已断开时显示"重新连接"；不传时只显示状态。 */
  onReconnect?: () => void;
  className?: string;
}

/**
 * SSE 连接状态：已连接、连接中、重连中、已断开。状态变化经 aria-live 告知读屏；断线后自动重连与按 Last-Event-ID 补齐
 * 由 lib/sse 完成，这里只显示结果，已断开（重试次数用尽或被关闭）时给出手动重连。
 */
export function StreamIndicator({ state, onReconnect, className }: StreamIndicatorProps) {
  const { kind, label } = STATE[state];
  return (
    <span
      data-slot="stream-indicator"
      className={cn('inline-flex items-center gap-2 text-12 text-ink-2', className)}
    >
      <span role="status" className="inline-flex items-center gap-1">
        <StatusGlyph kind={kind} />
        <span className="sr-only">实时推送</span>
        {label}
      </span>
      {state === 'closed' && onReconnect && (
        <Button size="sm" variant="ghost" className="h-6" onClick={onReconnect}>
          重新连接
        </Button>
      )}
    </span>
  );
}
