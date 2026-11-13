import { memo } from 'react';

import { cn } from '@/lib/cn';

import type { NodeShape, NodeTone, TimelineRow } from './model';
import { StatusShape } from './status-glyph';

/** 时间栏宽度（px）。 */
export const TIME_COLUMN = 64;
/** 子午线（0 号线）距图形列左边缘的距离，以及相邻线的间距。取 .5 让 1px 线落在像素上。 */
const LANE_X0 = 14.5;
const LANE_GAP = 16;
/** 节点中心距行顶的距离：与内容第一行（13px/20px）的中线对齐。 */
export const NODE_Y = 18;

export function laneX(lane: number): number {
  return LANE_X0 + lane * LANE_GAP;
}

/** 图形列宽度：两侧各留出子午线到边缘的距离。 */
export function graphWidth(laneCount: number): number {
  return Math.ceil(LANE_X0 * 2 + (Math.max(1, laneCount) - 1) * LANE_GAP);
}

const TONE_CLASS: Record<NodeTone, string> = {
  ink: 'text-ink',
  muted: 'text-ink-2',
  accent: 'text-accent',
  brass: 'text-brass',
  success: 'text-success',
  danger: 'text-danger',
  reschedule: 'text-reschedule',
  terminated: 'text-terminated',
};

/** 节点周围留空的半径：线在节点处断开；刻度类节点（测量、压缩）让线穿过。 */
function clearance(shape: NodeShape | null): number {
  if (shape === null || shape === 'measure' || shape === 'compacted') return 0;
  return shape === 'pending' ? 7 : 6;
}

function Node({ shape, tone }: { shape: NodeShape; tone: NodeTone }) {
  switch (shape) {
    case 'input':
      return (
        <rect x="-3.5" y="-3.5" width="7" height="7" fill="none" stroke="currentColor" strokeWidth="1.5" />
      );
    case 'message':
      return <circle r="3.75" fill="currentColor" />;
    case 'thinking':
      return <circle r="3" fill="none" stroke="currentColor" strokeWidth="1.25" />;
    case 'tool':
      return <path d="M-2.75 -4 4 0-2.75 4z" fill="currentColor" />;
    case 'result':
      return <StatusShape kind={tone === 'success' ? 'success' : tone === 'accent' ? 'idle' : 'failure'} />;
    case 'measure':
      return <path d="M-4 0H4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />;
    case 'pending':
      return <StatusShape kind="requires_action" />;
    case 'idle':
      return tone === 'danger' ? <StatusShape kind="failure" /> : <StatusShape kind="idle" />;
    case 'reschedule':
      return <StatusShape kind="rescheduled" />;
    case 'error':
      return <StatusShape kind="failure" />;
    case 'terminated':
      return <StatusShape kind="terminated" />;
    case 'compacted':
      return (
        <path
          d="M-4.5 -1.5 4.5 -4.5M-4.5 4.5 4.5 1.5"
          stroke="currentColor"
          strokeWidth="1.25"
          strokeLinecap="round"
        />
      );
    case 'thread':
      return <circle r="3" fill="currentColor" />;
  }
}

function laneClass(lane: number): string {
  return lane === 0 ? 'text-meridian' : 'text-lane';
}

export interface TimelineGraphProps {
  row: TimelineRow;
  /** 线条用虚线：间隔行与"此刻"之前的延伸。 */
  dashed?: boolean;
}

/**
 * 一行的图形：贯穿的线、节点所在线的上下两段、分叉与并回的圆弧、子午线左侧的刻度、轮次的主刻度。
 * 每行独立绘制，虚拟滚动只渲染可见的行。
 */
export const TimelineGraph = memo(function TimelineGraph({ row, dashed = false }: TimelineGraphProps) {
  const nodeX = row.lane >= 0 ? laneX(row.lane) : 0;
  const gap = clearance(row.shape);
  const dash = dashed ? '2 3' : undefined;
  const isTurn = row.kind === 'turn';

  return (
    <svg aria-hidden className="pointer-events-none absolute inset-0 h-full w-full overflow-visible">
      {row.through.map((lane) => (
        <line
          key={lane}
          x1={laneX(lane)}
          x2={laneX(lane)}
          y1={0}
          y2="100%"
          className={laneClass(lane)}
          stroke="currentColor"
          strokeDasharray={dash}
          shapeRendering="crispEdges"
        />
      ))}

      {row.kind === 'gap' && (
        <path
          d={`M${laneX(0) - 5} 15 ${laneX(0) + 5} 11M${laneX(0) - 5} 21 ${laneX(0) + 5} 17`}
          className="text-meridian"
          stroke="currentColor"
          strokeWidth="1.25"
          strokeLinecap="round"
        />
      )}

      {row.lane >= 0 && row.above && (
        <line
          x1={nodeX}
          x2={nodeX}
          y1={0}
          y2={NODE_Y - gap}
          className={laneClass(row.lane)}
          stroke="currentColor"
          shapeRendering="crispEdges"
        />
      )}
      {row.lane >= 0 && row.below && (
        <line
          x1={nodeX}
          x2={nodeX}
          y1={NODE_Y + gap}
          y2="100%"
          className={laneClass(row.lane)}
          stroke="currentColor"
          shapeRendering="crispEdges"
        />
      )}

      {row.forkFrom !== null && (
        <path
          d={`M${laneX(row.forkFrom)} 0C${laneX(row.forkFrom)} 9 ${nodeX} 3 ${nodeX} ${NODE_Y - gap}`}
          className="text-lane"
          stroke="currentColor"
          fill="none"
        />
      )}
      {row.mergeInto !== null && (
        <path
          d={`M${nodeX} ${NODE_Y + gap}C${nodeX} ${NODE_Y + 16} ${laneX(row.mergeInto)} ${NODE_Y + 12} ${laneX(row.mergeInto)} ${NODE_Y + 22}`}
          className="text-lane"
          stroke="currentColor"
          fill="none"
        />
      )}

      {/* 时间栏右缘的分划：每个事件一条短刻度，像尺子的分划；轮次开始是横跨时间栏的主刻度。 */}
      {row.kind === 'event' && (
        <line
          x1={0}
          x2={5}
          y1={NODE_Y + 0.5}
          y2={NODE_Y + 0.5}
          className="text-meridian"
          stroke="currentColor"
          shapeRendering="crispEdges"
        />
      )}
      {isTurn && (
        <line
          x1={-TIME_COLUMN + 8}
          x2={laneX(0) + 7}
          y1={NODE_Y + 0.5}
          y2={NODE_Y + 0.5}
          className="text-ink"
          stroke="currentColor"
          strokeWidth="1.5"
        />
      )}

      {row.shape !== null && row.lane >= 0 && (
        <g transform={`translate(${nodeX} ${NODE_Y})`} className={cn(TONE_CLASS[row.tone])}>
          <Node shape={row.shape} tone={row.tone} />
        </g>
      )}
    </svg>
  );
});
