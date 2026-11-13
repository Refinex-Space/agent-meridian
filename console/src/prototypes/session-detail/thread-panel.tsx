import { useMemo } from 'react';

import type { TimelineModel } from '@/components/domain/session-timeline';
import { StatusGlyph } from '@/components/domain/status-badge';
import { MAIN_THREAD_KEY, ThreadTree, threadTreeItems } from '@/components/domain/thread-tree';
import { ScrollArea } from '@/components/ui/scroll-area';
import type { SessionStatus } from '@/lib/api/types';
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

/** 剩余时间按分钟显示（不足一分钟时按秒）。 */
function remaining(ms: number): string {
  return formatDuration(ms >= 60_000 ? Math.floor(ms / 60_000) * 60_000 : ms);
}

/** 左栏：线程树（选中线程跳到它的第一个事件）与本会话等待回应的动作。 */
export function ThreadPanel({ model, status, selectedId, onJump, now }: ThreadPanelProps) {
  const items = useMemo(() => threadTreeItems(model, status), [model, status]);
  const firstEvent = useMemo(() => {
    const map = new Map<string, string>();
    for (const thread of model.threads) {
      const id = model.rows[thread.firstRow]?.event?.id;
      if (id) map.set(thread.lane === 0 || thread.threadId === null ? MAIN_THREAD_KEY : thread.threadId, id);
    }
    return map;
  }, [model]);
  const selectedRow = selectedId === null ? undefined : model.rows[model.rowIndex.get(selectedId) ?? -1];
  const selectedThread = selectedRow?.event
    ? (model.threads.find((t) => t.threadId === selectedRow.event?.thread_id && t.lane > 0)?.threadId ??
      MAIN_THREAD_KEY)
    : null;
  const pending = [...model.actions.values()].filter((a) => a.pending);

  return (
    <ScrollArea className="h-full" label="线程与待处理动作">
      <div className="flex flex-col gap-6 px-3 py-4">
        <section className="flex flex-col gap-1">
          <h2 className="px-2 pb-1 text-13 font-semibold">线程</h2>
          <ThreadTree
            items={items}
            selectedId={selectedThread}
            onSelect={(id) => {
              const eventId = firstEvent.get(id);
              if (eventId) onJump(eventId);
            }}
          />
        </section>

        <section className="flex flex-col gap-1">
          <h2 className="px-2 pb-1 text-13 font-semibold">待处理</h2>
          {pending.length === 0 ? (
            <p className="px-2 text-13 text-ink-2">没有等待回应的动作</p>
          ) : (
            <ul className="flex flex-col gap-1">
              {pending.map((action) => {
                const left = Date.parse(action.summary.expires_at ?? '') - now;
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
                      {Number.isFinite(left) && (
                        <span className="col-start-2 text-12 text-ink-2 tabular-nums">
                          {left > 0 ? `${remaining(left)}后视为拒绝` : '已超时'}
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
    </ScrollArea>
  );
}
