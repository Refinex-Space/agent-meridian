import { useEffect, useState, type CSSProperties } from 'react';

import type { SessionEvent } from '@/lib/api/types';
import { cn } from '@/lib/cn';
import { formatDuration } from '@/lib/format';
import type { LiveBlock } from '@/lib/sse/live-blocks';

import { laneX, NODE_Y, TIME_COLUMN } from './timeline-graph';

export type OpenStatus = 'running' | 'requires_action' | 'rescheduled';

export interface NowMarkerProps {
  status: OpenStatus;
  lastEvent: SessionEvent | null;
  live: readonly LiveBlock[];
  /** 仍在运行的子线程所在的线。 */
  lanes: readonly number[];
  graphWidth: number;
  /** 当前时间的来源，测试中可替换。 */
  now?: () => number;
}

/** 每秒更新一次的距今时长；只有这一个组件随时间重绘。 */
function useElapsed(since: string | null, now: () => number): number | null {
  const [tick, setTick] = useState(() => now());
  useEffect(() => {
    const timer = window.setInterval(() => setTick(now()), 1000);
    return () => window.clearInterval(timer);
  }, [now]);
  // 取整到秒：每秒跳一次的计时不显示小数。
  return since === null ? null : Math.max(0, Math.floor((tick - Date.parse(since)) / 1000) * 1000);
}

function activity(status: OpenStatus, lastEvent: SessionEvent | null, live: readonly LiveBlock[]): string {
  if (status === 'requires_action') return '等待处理';
  if (status === 'rescheduled') return '等待其他节点重新执行';
  const block = live.at(-1);
  if (block) {
    if (block.kind === 'text' || block.kind === 'data') return '正在回复';
    if (block.kind === 'thinking') return '正在思考';
    if (block.kind === 'tool_use') return `正在生成 ${block.name ?? '工具'} 的入参`;
    return `正在执行 ${block.name ?? '工具'}`;
  }
  switch (lastEvent?.type) {
    case 'agent.tool_use':
    case 'agent.mcp_tool_use':
      return `正在执行 ${lastEvent.data.name}`;
    case 'session.thread_created':
    case 'session.thread_status_running':
      return '子智能体运行中';
    default:
      return '等待模型响应';
  }
}

const defaultNow = () => Date.now();

/**
 * "此刻"：会话仍在进行时，子午线末端以虚线延伸到黄铜色菱形；运行中时菱形以 2 秒周期呼吸，
 * 减少动效时静止。旁边是当前在做什么与距上一个事件的时长，正在生成的回复逐字出现在下方。
 */
export function NowMarker({ status, lastEvent, live, lanes, graphWidth, now = defaultNow }: NowMarkerProps) {
  const elapsed = useElapsed(lastEvent?.created_at ?? null, now);
  const columns: CSSProperties = { gridTemplateColumns: `${TIME_COLUMN}px ${graphWidth}px minmax(0, 1fr)` };
  const text = live.findLast((b) => b.kind === 'text' || b.kind === 'data')?.text ?? '';
  const x = laneX(0);

  return (
    <div className="grid min-h-12" style={columns} aria-live="polite" aria-atomic="false">
      <span className="bg-surface-inset" />
      <span className="relative" aria-hidden>
        <svg className="absolute inset-0 h-full w-full overflow-visible">
          <line
            x1={x}
            x2={x}
            y1={0}
            y2={NODE_Y - 8}
            className="text-meridian"
            stroke="currentColor"
            strokeDasharray="2 3"
          />
          {lanes.map((lane) => (
            <line
              key={lane}
              x1={laneX(lane)}
              x2={laneX(lane)}
              y1={0}
              y2="100%"
              className="text-lane"
              stroke="currentColor"
              strokeDasharray="2 3"
            />
          ))}
          <g transform={`translate(${x} ${NODE_Y})`} className="text-brass">
            <path
              d="M0 -6 6 0 0 6 -6 0z"
              fill="currentColor"
              className={cn(status === 'running' && 'motion-safe:animate-breath')}
              style={{ transformBox: 'fill-box', transformOrigin: 'center' }}
            />
          </g>
        </svg>
      </span>
      <span className="flex min-w-0 flex-col py-2 pr-4">
        <span className="flex items-baseline gap-2 text-13 leading-5">
          <span className="font-semibold text-brass-ink">此刻</span>
          <span className="text-ink">{activity(status, lastEvent, live)}</span>
          {elapsed !== null && (
            <span className="text-12 text-ink-2 tabular-nums">
              {status === 'requires_action' ? '已等待' : '距上一事件'} {formatDuration(elapsed)}
            </span>
          )}
        </span>
        {text && (
          <span className="mt-1 line-clamp-3 text-13 leading-5 whitespace-pre-line text-ink-2">{text}</span>
        )}
      </span>
    </div>
  );
}
