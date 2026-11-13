import { STOP_REASON_LABEL, StatusGlyph, type TimelineModel } from '@/components/domain/session-timeline';
import { Badge } from '@/components/ui/badge';
import type { SessionStatus } from '@/lib/api/types';
import { cn } from '@/lib/cn';
import { formatDuration } from '@/lib/format';

export interface ThreadPanelProps {
  model: TimelineModel;
  status: SessionStatus;
  /** 当前选中的事件 ID。 */
  selectedId: string | null;
  onJump: (eventId: string) => void;
  /** 当前时间（毫秒），用于显示动作的剩余时间。 */
  now: number;
}

/** 左栏：线程树与本会话的待处理动作（T20b 的 ThreadTree 落实正式组件）。 */
export function ThreadPanel({ model, status, selectedId, onJump, now }: ThreadPanelProps) {
  const pending = [...model.actions.values()].filter((a) => a.pending);
  return (
    <div className="flex h-full flex-col gap-6 overflow-y-auto px-3 py-4">
      <section className="flex flex-col gap-1">
        <h2 className="px-2 pb-1 text-13 font-semibold">线程</h2>
        <ul className="flex flex-col">
          {model.threads.map((thread) => {
            const first = model.rows[thread.firstRow]?.event;
            const kind =
              thread.lane === 0 ? (status === 'terminated' ? 'terminated' : status) : thread.status;
            const selected = first !== null && first !== undefined && first.id === selectedId;
            return (
              <li key={thread.threadId ?? 'main'}>
                <button
                  type="button"
                  disabled={!first}
                  onClick={() => first && onJump(first.id)}
                  className={cn(
                    'flex h-8 w-full items-center gap-2 rounded-control pr-2 text-left text-13',
                    thread.lane === 0 ? 'pl-2' : 'pl-6',
                    selected ? 'bg-accent-weak' : 'hover:bg-surface-hover',
                  )}
                >
                  <StatusGlyph kind={kind} />
                  <span className={cn('min-w-0 flex-1 truncate', thread.lane > 0 && 'font-mono text-12')}>
                    {thread.lane === 0 ? '主线程' : thread.agentName}
                  </span>
                  {thread.lane > 0 && thread.status === 'idle' && thread.stopReason !== 'end_turn' && (
                    <span className="text-12 text-danger">
                      {STOP_REASON_LABEL[thread.stopReason ?? 'error']}
                    </span>
                  )}
                </button>
              </li>
            );
          })}
        </ul>
      </section>

      <section className="flex flex-col gap-1">
        <h2 className="flex items-center gap-2 px-2 pb-1 text-13 font-semibold">
          待处理
          {pending.length > 0 && <Badge tone="brass">{pending.length}</Badge>}
        </h2>
        {pending.length === 0 ? (
          <p className="px-2 text-13 text-ink-2">没有等待回应的动作</p>
        ) : (
          <ul className="flex flex-col gap-1">
            {pending.map((action) => {
              const remaining = Date.parse(action.summary.expires_at ?? '') - now;
              const command = action.summary.input['command'];
              return (
                <li key={action.summary.id}>
                  <button
                    type="button"
                    onClick={() => onJump(action.requestedBy.id)}
                    className="grid w-full grid-cols-[12px_minmax(0,1fr)] items-center gap-x-2 gap-y-1 rounded-control px-2 py-2 text-left hover:bg-surface-hover"
                  >
                    <StatusGlyph kind="requires_action" />
                    <span className="flex items-center gap-2 text-13">
                      <span className="font-medium text-brass-ink">等待批准</span>
                      <span className="font-mono text-12">{action.summary.tool_name}</span>
                    </span>
                    {typeof command === 'string' && (
                      <span className="col-start-2 line-clamp-2 font-mono text-12 break-all text-ink-2">
                        {command}
                      </span>
                    )}
                    {Number.isFinite(remaining) && (
                      <span className="col-start-2 text-12 text-ink-2 tabular-nums">
                        {remaining > 0
                          ? `${formatDuration(Math.floor(remaining / 60_000) * 60_000 || remaining)}后视为拒绝`
                          : '已超时'}
                      </span>
                    )}
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </section>
    </div>
  );
}
