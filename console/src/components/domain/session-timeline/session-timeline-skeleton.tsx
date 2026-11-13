import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/cn';

import { graphWidth, laneX, NODE_Y, TIME_COLUMN } from './timeline-graph';

/** 骨架的行：高度与真实行的估算一致（单行 40，带预览 64），内容宽度错落。 */
const ROWS = [
  { height: 64, title: 'w-16', detail: 'w-3/5' },
  { height: 36, title: 'w-12', detail: null },
  { height: 64, title: 'w-12', detail: 'w-4/5' },
  { height: 40, title: 'w-24', detail: null },
  { height: 64, title: 'w-28', detail: 'w-2/5' },
  { height: 64, title: 'w-20', detail: 'w-3/4' },
  { height: 40, title: 'w-24', detail: null },
  { height: 84, title: 'w-12', detail: 'w-11/12' },
] as const;

/**
 * 时间轴的加载骨架：与最终布局同一套几何（时间栏、子午线位置、行高、内容起点），加载完成时不跳动。
 * 只做不透明度呼吸，减少动效时静止。
 */
export function SessionTimelineSkeleton({ className }: { className?: string }) {
  const width = graphWidth(1);
  return (
    <div className={cn('relative overflow-hidden', className)} aria-busy="true" aria-label="正在加载事件">
      {ROWS.map((row, i) => (
        <div
          key={i}
          className="grid"
          style={{ gridTemplateColumns: `${TIME_COLUMN}px ${width}px minmax(0, 1fr)`, height: row.height }}
        >
          <span className="flex justify-end bg-surface-inset pt-3 pr-3">
            {i % 2 === 0 && <Skeleton className="h-3 w-12" />}
          </span>
          <span className="relative">
            <svg aria-hidden className="absolute inset-0 h-full w-full">
              <line
                x1={laneX(0)}
                x2={laneX(0)}
                y1={0}
                y2="100%"
                className="text-rule"
                stroke="currentColor"
              />
              <circle cx={laneX(0)} cy={NODE_Y} r="3.5" className="text-rule" fill="currentColor" />
            </svg>
          </span>
          <span className="flex flex-col gap-2 pt-3 pr-4">
            <Skeleton className={cn('h-3', row.title)} />
            {row.detail && <Skeleton className={cn('h-3', row.detail)} />}
          </span>
        </div>
      ))}
    </div>
  );
}
