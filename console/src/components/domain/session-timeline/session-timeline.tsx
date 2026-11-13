import { useVirtualizer } from '@tanstack/react-virtual';
import {
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
} from 'react';

import { Button } from '@/components/ui/button';
import { ArrowLineDownIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import type { SessionEvent, SessionStatus } from '@/lib/api/types';
import { cn } from '@/lib/cn';
import { formatClock } from '@/lib/format';
import type { LiveBlock } from '@/lib/sse/live-blocks';

import { describeRow, type DescribeContext, type RowText } from './describe';
import { buildTimeline, type ActionInfo, type TimelineRow } from './model';
import { NowMarker, type OpenStatus } from './now-marker';
import { graphWidth } from './timeline-graph';
import { TimelineRowView } from './timeline-row';

export interface SessionTimelineProps {
  /** 按 seq 升序的持久化事件（`GET …/events` 加 SSE 追加）。 */
  events: readonly SessionEvent[];
  /** 会话状态：运行中、等待处理、重新调度时显示"此刻"。 */
  status: SessionStatus;
  /** 正在生成的内容块（SSE 的 event_start、event_delta）。 */
  live?: readonly LiveBlock[];
  /** 模型 ID 到显示名。 */
  modelNames?: Readonly<Record<string, string>>;
  /** 选中的事件 ID。 */
  selectedId?: string | null;
  onSelect?: (event: SessionEvent) => void;
  /** 在行内批准或拒绝待处理动作；不提供时只显示，不能操作（例如没有审批权限）。 */
  onRespond?: (actionId: string, approved: boolean) => void;
  /** 正在提交回应的动作 ID：对应按钮显示进度，其他按钮禁用。 */
  respondingActionId?: string | null;
  /** 当前时间的来源，测试中可替换。 */
  now?: () => number;
  className?: string;
}

function isOpen(status: SessionStatus): status is OpenStatus {
  return status === 'running' || status === 'requires_action' || status === 'rescheduled';
}
const EMPTY_LIVE: readonly LiveBlock[] = [];
const NO_ACTIONS: readonly ActionInfo[] = [];

function estimateRow(row: TimelineRow | undefined): number {
  if (!row) return 56;
  if (row.kind === 'gap') return 32;
  if (row.kind === 'turn') return 36;
  switch (row.event?.type) {
    case 'agent.message':
      return 84;
    case 'user.message':
    case 'agent.thinking':
    case 'agent.tool_use':
    case 'agent.tool_result':
    case 'session.status_requires_action':
      return 64;
    default:
      return 40;
  }
}

/** 离底部多近算"在底部"：在底部时新事件到达会自动跟随。 */
const FOLLOW_THRESHOLD = 48;

/**
 * 会话时间轴：一条子午线贯穿事件序列，刻度标出时间，子线程从主线分叉，运行中的"此刻"用黄铜色标记。
 * 新事件依次落位（200ms ease-out，减少动效时只有不透明度变化）；虚拟滚动，1 万条事件时依然流畅。
 * 键盘：方向键上下移动选中，Home、End 到首尾。
 */
export function SessionTimeline({
  events,
  status,
  live = EMPTY_LIVE,
  modelNames,
  selectedId = null,
  onSelect,
  onRespond,
  respondingActionId = null,
  now,
  className,
}: SessionTimelineProps) {
  const open = isOpen(status);
  const model = useMemo(() => buildTimeline(events, { open }), [events, open]);
  const ctx: DescribeContext = useMemo(
    () => ({ model, ...(modelNames ? { modelNames } : {}) }),
    [model, modelNames],
  );
  // 行的文字按键缓存：模型不变时同一行拿到同一个对象，行组件的 memo 才有效。
  const texts = useMemo(() => ({ ctx, cache: new Map<string, RowText>() }), [ctx]);
  const textOf = (row: TimelineRow): RowText => {
    let text = texts.cache.get(row.key);
    if (!text) {
      text = describeRow(row, texts.ctx);
      texts.cache.set(row.key, text);
    }
    return text;
  };
  const width = graphWidth(model.laneCount);
  const count = model.rows.length + (open ? 1 : 0);
  const listId = useId();

  const scrollRef = useRef<HTMLDivElement>(null);
  // 本项目不启用 React Compiler；这条规则只提示编译器会跳过本组件。
  // eslint-disable-next-line react-hooks/incompatible-library
  const virtualizer = useVirtualizer({
    count,
    getScrollElement: () => scrollRef.current,
    estimateSize: (index) => estimateRow(model.rows[index]),
    getItemKey: (index) => model.rows[index]?.key ?? 'now',
    overscan: 8,
  });

  // 新到达的行：与上一次渲染的行比较，本批按顺序依次落位；1 秒后清除，滚动回来重新挂载时不再播放。
  const previousKeys = useRef<Set<string> | null>(null);
  const [arrivals, setArrivals] = useState<ReadonlyMap<string, number>>(new Map());
  const atBottom = useRef(true);
  const [unseen, setUnseen] = useState(0);

  useLayoutEffect(() => {
    const keys = new Set(model.rows.map((r) => r.key));
    const previous = previousKeys.current;
    previousKeys.current = keys;
    if (previous === null) {
      if (model.rows.length > 0) virtualizer.scrollToIndex(count - 1, { align: 'end' });
      return;
    }
    const fresh = model.rows.filter((r) => !previous.has(r.key) && r.kind !== 'gap').map((r) => r.key);
    if (fresh.length === 0) return;
    setArrivals(new Map(fresh.map((key, i) => [key, i])));
    if (atBottom.current) virtualizer.scrollToIndex(count - 1, { align: 'end' });
    else setUnseen((n) => n + fresh.length);
  }, [model, count, virtualizer]);

  useEffect(() => {
    if (arrivals.size === 0) return undefined;
    const timer = window.setTimeout(() => setArrivals(new Map()), 1000);
    return () => window.clearTimeout(timer);
  }, [arrivals]);

  const onScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    atBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < FOLLOW_THRESHOLD;
    if (atBottom.current) setUnseen(0);
  }, []);

  const jumpToNow = useCallback(() => {
    virtualizer.scrollToIndex(count - 1, { align: 'end' });
    atBottom.current = true;
    setUnseen(0);
  }, [count, virtualizer]);

  const select = useCallback(
    (key: string) => {
      const index = model.rowIndex.get(key);
      const event = index === undefined ? undefined : model.rows[index]?.event;
      if (event) onSelect?.(event);
    },
    [model, onSelect],
  );

  // 键盘：在可选的行之间移动，并把焦点交给新选中行的按钮（虚拟滚动需要先滚到那一行再聚焦）。
  const selectedIndex = selectedId === null ? -1 : (model.rowIndex.get(selectedId) ?? -1);
  const focusAfterMove = useRef(false);
  const onKeyDown = useCallback(
    (e: KeyboardEvent<HTMLButtonElement>) => {
      const selectable = (i: number) => model.rows[i]?.kind !== 'gap' && model.rows[i]?.event;
      let target: number;
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        const step = e.key === 'ArrowDown' ? 1 : -1;
        let i = selectedIndex === -1 ? (step > 0 ? -1 : model.rows.length) : selectedIndex;
        do i += step;
        while (i >= 0 && i < model.rows.length && !selectable(i));
        target = i;
      } else if (e.key === 'Home') {
        target = model.rows.findIndex((_, i) => selectable(i));
      } else if (e.key === 'End') {
        target = model.rows.findLastIndex((_, i) => selectable(i));
      } else {
        return;
      }
      e.preventDefault();
      const row = model.rows[target];
      if (!row?.event) return;
      focusAfterMove.current = true;
      virtualizer.scrollToIndex(target, { align: 'auto' });
      onSelect?.(row.event);
    },
    [model, selectedIndex, onSelect, virtualizer],
  );

  useEffect(() => {
    if (!focusAfterMove.current || selectedId === null) return;
    focusAfterMove.current = false;
    const id = `${listId}-${selectedId}`;
    requestAnimationFrame(() => document.getElementById(id)?.focus({ preventScroll: true }));
  }, [selectedId, listId]);

  const items = virtualizer.getVirtualItems();
  const tabbableIndex =
    selectedIndex >= 0 ? selectedIndex : model.rows.findLastIndex((r) => r.kind !== 'gap');
  const runningLanes = useMemo(
    () => model.threads.filter((t) => t.lane > 0 && t.status === 'running').map((t) => t.lane),
    [model],
  );

  return (
    <div className={cn('relative flex min-h-0 flex-col', className)}>
      <div ref={scrollRef} onScroll={onScroll} className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
        <div
          role="list"
          aria-label="事件时间线"
          className="relative w-full"
          style={{ height: virtualizer.getTotalSize() }}
        >
          {items.map((item) => {
            const row = model.rows[item.index];
            return (
              <div
                key={item.key}
                role="listitem"
                aria-setsize={count}
                aria-posinset={item.index + 1}
                data-index={item.index}
                ref={virtualizer.measureElement}
                className={cn(
                  'absolute top-0 left-0 w-full',
                  !row && 'transition-transform duration-200 ease-out motion-reduce:transition-none',
                )}
                style={{ transform: `translateY(${item.start}px)` }}
              >
                {row ? (
                  <TimelineRowView
                    row={row}
                    text={textOf(row)}
                    showTime={showTimeFor(model.rows, item.index)}
                    selected={row.event !== null && row.event.id === selectedId}
                    tabbable={item.index === tabbableIndex}
                    graphWidth={width}
                    buttonId={`${listId}-${row.key}`}
                    pendingActions={pendingActionsOf(row, model.actions)}
                    respondingActionId={respondingActionId}
                    arrival={arrivals.get(row.key) ?? null}
                    onSelect={select}
                    onKeyDown={onKeyDown}
                    onRespond={onRespond}
                  />
                ) : (
                  isOpen(status) && (
                    <NowMarker
                      status={status}
                      lastEvent={model.lastEvent}
                      live={live}
                      lanes={runningLanes}
                      graphWidth={width}
                      {...(now ? { now } : {})}
                    />
                  )
                )}
              </div>
            );
          })}
        </div>
      </div>
      {unseen > 0 && (
        <div className="pointer-events-none absolute inset-x-0 bottom-4 flex justify-center">
          <Button
            size="sm"
            variant="secondary"
            className="pointer-events-auto shadow-overlay"
            icon={<Icon glyph={ArrowLineDownIcon} />}
            onClick={jumpToNow}
          >
            {unseen} 条新事件
          </Button>
        </div>
      )}
    </div>
  );
}

/** 与上一行同一秒的不重复显示时刻（刻度仍在），间隔行之后总是显示。 */
function showTimeFor(rows: readonly TimelineRow[], index: number): boolean {
  const row = rows[index];
  if (!row?.event) return false;
  const previous = rows[index - 1];
  if (!previous?.event) return true;
  return formatClock(previous.event.created_at) !== formatClock(row.event.created_at);
}

function pendingActionsOf(row: TimelineRow, actions: ReadonlyMap<string, ActionInfo>): readonly ActionInfo[] {
  if (row.event?.type !== 'session.status_requires_action') return NO_ACTIONS;
  const pending = row.event.data.actions
    .map((a) => actions.get(a.id))
    .filter((a): a is ActionInfo => a?.pending === true);
  return pending.length > 0 ? pending : NO_ACTIONS;
}
