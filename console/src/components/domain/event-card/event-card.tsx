import { useMemo } from 'react';

import type { SessionEvent } from '@/lib/api/types';
import { cn } from '@/lib/cn';
import { formatClock, formatInstant } from '@/lib/format';

import { buildTimeline, describeRow, NodeGlyph, type RowText } from '../session-timeline';

export interface EventCardProps {
  event: SessionEvent;
  /**
   * 同一会话中排在它之前、描述它需要的事件：工具结果对应的调用（算耗时、取工具名）、模型请求的开始、
   * 回应对应的待处理动作。没有时按事件自身描述。
   */
  context?: readonly SessionEvent[];
  /** 模型 ID 到显示名。 */
  modelNames?: Readonly<Record<string, string>>;
  /** 子线程的名称；主线程不传。 */
  threadName?: string;
  /** 给出时整张卡片可点击（例如在检查器中打开）。 */
  onSelect?: () => void;
  selected?: boolean;
  /** 默认带 1px 刻线边框；放进列表时用 false，由列表画分隔线。 */
  bordered?: boolean;
  className?: string;
}

const STATE_TONE: Record<string, string> = {
  success: 'text-success',
  danger: 'text-danger',
  brass: 'font-medium text-brass-ink',
  accent: 'text-accent',
};

/** 不单独成行的事件（模型请求开始、用量）没有时间轴文案，退回事件类型。 */
function fallback(event: SessionEvent): RowText {
  return { title: event.type, subject: null, detail: null, state: null, metrics: [] };
}

/**
 * 单个事件的摘要：与时间轴同一套节点符号与文案（按事件类型选择描述），标题、主体、一行预览、结果与右侧度量。
 * 用在待处理动作、搜索结果等离开时间轴的地方。
 */
export function EventCard({
  event,
  context = [],
  modelNames,
  threadName,
  onSelect,
  selected = false,
  bordered = true,
  className,
}: EventCardProps) {
  const { row, text } = useMemo(() => {
    const model = buildTimeline([...context, event]);
    const index = model.rowIndex.get(event.id);
    const found = index === undefined ? undefined : model.rows[index];
    return { row: found, text: found ? describeRow(found, { model, modelNames }) : fallback(event) };
  }, [context, event, modelNames]);

  const body = (
    <>
      <span className="flex h-5 w-3 items-center justify-center">
        {row?.shape && <NodeGlyph shape={row.shape} tone={row.tone} />}
      </span>
      <span className="flex min-w-0 flex-col gap-1 text-left">
        <span className="flex min-w-0 items-center gap-2 text-13 leading-5">
          <span className={cn('shrink-0 font-medium', row?.tone === 'muted' && 'font-normal text-ink-2')}>
            {text.title}
          </span>
          {text.subject && (
            <span className={cn('truncate', text.subject.mono && 'font-mono text-12')}>
              {text.subject.text}
            </span>
          )}
          {text.state && (
            <span className={cn('shrink-0 text-12', STATE_TONE[text.state.tone] ?? 'text-ink-2')}>
              {text.state.text}
            </span>
          )}
        </span>
        {text.detail && (
          <span
            className={cn(
              'line-clamp-2 text-13 break-words text-ink-2',
              text.detail.mono && 'font-mono text-12 break-all',
            )}
          >
            {text.detail.text}
          </span>
        )}
        {threadName && <span className="text-12 text-ink-2">子线程 {threadName}</span>}
      </span>
      <span className="flex flex-col items-end gap-1 text-12 whitespace-nowrap text-ink-2 tabular-nums">
        <time dateTime={event.created_at} title={formatInstant(event.created_at)}>
          {formatClock(event.created_at)}
        </time>
        {text.metrics.length > 0 && (
          <span className="flex gap-3">
            {text.metrics.map((m) => (
              <span key={m.text} title={m.title}>
                {m.text}
              </span>
            ))}
          </span>
        )}
      </span>
    </>
  );

  const shell = cn(
    'grid w-full grid-cols-[12px_minmax(0,1fr)_auto] items-start gap-3 px-3 py-2',
    bordered && 'rounded-panel border border-rule bg-surface',
    selected && 'border-accent bg-accent-weak',
    className,
  );

  if (onSelect) {
    return (
      <button
        type="button"
        data-slot="event-card"
        aria-pressed={selected}
        onClick={onSelect}
        className={cn(
          shell,
          'transition-colors duration-150 ease-out hover:bg-surface-hover',
          selected && 'hover:bg-accent-weak',
        )}
      >
        {body}
      </button>
    );
  }
  return (
    <div data-slot="event-card" className={shell}>
      {body}
    </div>
  );
}
