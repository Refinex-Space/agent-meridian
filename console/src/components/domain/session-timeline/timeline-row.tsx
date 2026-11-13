import { memo, type CSSProperties, type KeyboardEvent } from 'react';

import { Button } from '@/components/ui/button';
import { cn } from '@/lib/cn';
import { formatClock } from '@/lib/format';

import type { RowText } from './describe';
import type { ActionInfo, NodeTone, TimelineRow } from './model';
import { TIME_COLUMN, TimelineGraph } from './timeline-graph';

const STATE_TONE: Record<NodeTone, string> = {
  ink: 'text-ink',
  muted: 'text-ink-2',
  accent: 'text-accent',
  brass: 'text-brass-ink',
  success: 'text-success',
  danger: 'text-danger',
  reschedule: 'text-ink-2',
  terminated: 'text-ink-2',
};

export interface TimelineRowViewProps {
  row: TimelineRow;
  text: RowText;
  /** 这一行显示时刻；与上一行同一秒时不重复显示。 */
  showTime: boolean;
  selected: boolean;
  /** 漫游焦点：只有一行的按钮在 Tab 序列中。 */
  tabbable: boolean;
  graphWidth: number;
  buttonId: string;
  /** 本行等待回应的动作（只有等待处理的行有）。 */
  pendingActions: readonly ActionInfo[];
  respondingActionId: string | null;
  /** 新到达的行在本批中的次序，用于依次落位；不是新到达的行为 null。 */
  arrival: number | null;
  onSelect: (key: string) => void;
  /** 方向键、Home、End 在行之间移动选中。 */
  onKeyDown: (e: KeyboardEvent<HTMLButtonElement>) => void;
  onRespond?: ((actionId: string, approved: boolean) => void) | undefined;
}

/** 时间轴的一行。整行可点选；选中行的内容在检查器中展开。 */
export const TimelineRowView = memo(function TimelineRowView({
  row,
  text,
  showTime,
  selected,
  tabbable,
  graphWidth,
  buttonId,
  pendingActions,
  respondingActionId,
  arrival,
  onSelect,
  onKeyDown,
  onRespond,
}: TimelineRowViewProps) {
  const pending = pendingActions.length > 0;
  // 测量类的行（推理、思考）退到次要层级，让调用、结果与回复更醒目。
  const secondary = row.shape === 'measure' || row.shape === 'thinking';
  const columns: CSSProperties = {
    gridTemplateColumns: `${TIME_COLUMN}px ${graphWidth}px minmax(0, 1fr) auto`,
  };
  const arrivalStyle: CSSProperties | undefined =
    arrival === null ? undefined : { animationDelay: `${arrival * 60}ms` };

  if (row.kind === 'gap') {
    return (
      <div className="grid h-8" style={columns} aria-hidden>
        <div className="bg-surface-inset" />
        <div className="relative">
          <TimelineGraph row={row} dashed />
        </div>
        <div className="col-span-2 flex items-center text-12 text-ink-2">{text.title}</div>
      </div>
    );
  }

  const time = row.event ? formatClock(row.event.created_at) : '';
  return (
    <div
      className={cn(
        'group/row relative',
        arrival !== null && 'motion-safe:animate-arrive motion-reduce:animate-fade-in-slow',
      )}
      style={arrivalStyle}
    >
      <button
        type="button"
        id={buttonId}
        tabIndex={tabbable ? 0 : -1}
        aria-current={selected ? 'true' : undefined}
        onClick={() => onSelect(row.key)}
        onKeyDown={onKeyDown}
        className={cn(
          'grid w-full cursor-default text-left focus-visible:outline-offset-[-2px]',
          row.kind === 'turn' ? 'min-h-9' : 'min-h-10',
          selected ? 'bg-accent-weak' : pending ? 'bg-brass-weak' : 'hover:bg-surface-hover',
        )}
        style={columns}
      >
        <span
          className={cn(
            'pt-2 pr-3 text-right text-12 leading-5 text-ink-2 tabular-nums',
            selected ? 'bg-accent-weak' : 'bg-surface-inset',
          )}
        >
          {showTime ? time : <span className="sr-only">{time}</span>}
        </span>
        <span className="relative">
          <TimelineGraph row={row} />
        </span>
        {row.kind === 'turn' ? (
          <span className="flex items-center gap-3 pt-2 pr-4 pb-2">
            <span className="text-12 leading-5 font-semibold text-ink">{text.title}</span>
            {text.state && <span className="text-12 leading-5 text-ink-2">{text.state.text}</span>}
            <span aria-hidden className="h-px flex-1 bg-rule" />
          </span>
        ) : (
          <span className="flex min-w-0 flex-col py-2 pr-4">
            <span className="flex min-w-0 items-baseline gap-2 text-13 leading-5">
              <span
                className={cn(
                  'shrink-0',
                  pending ? 'font-medium text-brass-ink' : secondary ? 'text-ink-2' : 'font-medium text-ink',
                )}
              >
                {text.title}
              </span>
              {text.subject && (
                <span
                  className={cn(
                    'truncate',
                    text.subject.mono && 'font-mono text-12',
                    secondary ? 'text-ink-2' : 'text-ink',
                  )}
                >
                  {text.subject.text}
                </span>
              )}
              {text.state && (
                <span className={cn('shrink-0 text-12', STATE_TONE[text.state.tone])}>{text.state.text}</span>
              )}
            </span>
            {text.detail && (
              <span
                className={cn(
                  'mt-1 max-w-180 text-ink-2',
                  text.detail.mono
                    ? 'line-clamp-2 font-mono text-12 leading-5 break-all whitespace-pre-wrap'
                    : row.event?.type === 'agent.message'
                      ? 'line-clamp-3 text-13 leading-5 whitespace-pre-line'
                      : 'line-clamp-2 text-13 leading-5',
                )}
              >
                {text.detail.text}
              </span>
            )}
          </span>
        )}
        {row.kind !== 'turn' &&
          (pending && onRespond ? (
            // 给右上角的批准、拒绝按钮留出位置。
            <span aria-hidden className="block w-32" />
          ) : (
            <span className="flex items-start justify-end gap-3 pt-2 pr-4 text-12 leading-5 whitespace-nowrap text-ink-2 tabular-nums">
              {text.metrics.map((metric) => (
                <span key={metric.text} title={metric.title}>
                  {metric.text}
                </span>
              ))}
            </span>
          ))}
      </button>
      {pending && onRespond && (
        <span className="absolute top-2 right-4 flex gap-2">
          {pendingActions.map((action) =>
            action.summary.type === 'tool_confirmation' || action.summary.type === 'plan_exit' ? (
              <span key={action.summary.id} className="flex gap-2">
                <Button
                  size="sm"
                  variant="secondary"
                  disabled={respondingActionId !== null}
                  onClick={() => onRespond(action.summary.id, false)}
                >
                  拒绝
                </Button>
                <Button
                  size="sm"
                  variant="primary"
                  pending={respondingActionId === action.summary.id}
                  disabled={respondingActionId !== null && respondingActionId !== action.summary.id}
                  onClick={() => onRespond(action.summary.id, true)}
                >
                  批准
                </Button>
              </span>
            ) : null,
          )}
        </span>
      )}
    </div>
  );
});
