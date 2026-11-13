import { AnimatePresence, motion, useReducedMotion } from 'motion/react';
import { useRef, useState, type KeyboardEvent, type PointerEvent, type ReactNode } from 'react';

import { cn } from '@/lib/cn';

/** 一栏的宽度设置（px）。 */
export interface PaneWidth {
  initial: number;
  min: number;
  max: number;
}

export interface WorkbenchProps {
  /** 工作台头部（对象、状态、用量与操作），横跨三栏。 */
  header?: ReactNode;
  /** 头部下方的就地提示（例如回应失败），横跨三栏。 */
  notice?: ReactNode;
  /** 左栏：导航性质的内容（线程树、待处理动作）。 */
  left?: { label: string; width: PaneWidth; content: ReactNode };
  /** 中栏：主体，占据剩余宽度。 */
  main: { label: string; content: ReactNode };
  /** 右栏：检查器；`open` 为 false 时收起（宽度动画回应操作），`content` 自带地标、标题栏与收起按钮。 */
  right?: { label: string; width: PaneWidth; open: boolean; content: ReactNode };
}

const KEY_STEP = 16;

function clamp(value: number, { min, max }: PaneWidth): number {
  return Math.min(max, Math.max(min, value));
}

/**
 * 栏之间的分隔条：拖动或用方向键调整一侧栏的宽度（WAI-ARIA 可聚焦的 separator）。分隔条就是两栏之间那条 1px 刻线，
 * 悬停与拖动时加粗为 2px 海图蓝；命中区 8px，不占布局宽度。
 */
function ResizeHandle({
  label,
  width,
  bounds,
  side,
  onChange,
}: {
  label: string;
  width: number;
  bounds: PaneWidth;
  /** 被调整的栏在分隔条的哪一侧：left 时向右拖变宽，right 时向左拖变宽。 */
  side: 'left' | 'right';
  onChange: (width: number) => void;
}) {
  const drag = useRef<{ x: number; width: number } | null>(null);
  const [dragging, setDragging] = useState(false);
  const direction = side === 'left' ? 1 : -1;

  const onPointerDown = (e: PointerEvent<HTMLDivElement>) => {
    e.currentTarget.setPointerCapture(e.pointerId);
    drag.current = { x: e.clientX, width };
    setDragging(true);
  };
  const onPointerMove = (e: PointerEvent<HTMLDivElement>) => {
    if (!drag.current) return;
    onChange(clamp(drag.current.width + (e.clientX - drag.current.x) * direction, bounds));
  };
  const onPointerUp = () => {
    drag.current = null;
    setDragging(false);
  };
  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const step = e.shiftKey ? KEY_STEP * 4 : KEY_STEP;
    if (e.key === 'ArrowLeft') onChange(clamp(width - step * direction, bounds));
    else if (e.key === 'ArrowRight') onChange(clamp(width + step * direction, bounds));
    else if (e.key === 'Home') onChange(bounds.min);
    else if (e.key === 'End') onChange(bounds.max);
    else return;
    e.preventDefault();
  };

  return (
    // 可聚焦的 separator 在 WAI-ARIA 1.2 中是控件（窗格分隔条模式），jsx-a11y 仍按不可交互的角色检查。
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions
    <div
      role="separator"
      aria-orientation="vertical"
      aria-label={label}
      aria-valuenow={Math.round(width)}
      aria-valuemin={bounds.min}
      aria-valuemax={bounds.max}
      // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- 同上：可聚焦的分隔条
      tabIndex={0}
      data-dragging={dragging || undefined}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerUp}
      onKeyDown={onKeyDown}
      className={cn(
        'group relative z-10 -mx-1 w-2 shrink-0 cursor-col-resize touch-none outline-offset-[-2px]',
        dragging && 'select-none',
      )}
    >
      <span
        aria-hidden
        className={cn(
          'absolute inset-y-0 left-1/2 w-px -translate-x-1/2 bg-rule transition-[width,background-color] duration-150 ease-out',
          'group-hover:w-0.5 group-hover:bg-accent group-focus-visible:w-0.5 group-focus-visible:bg-accent',
          dragging && 'w-0.5 bg-accent',
        )}
      />
    </div>
  );
}

/**
 * 工作台模板（会话详情、Playground）：头部横跨三栏，下面是左栏、主体与可收起的右栏；左右两栏可拖动调宽。
 */
export function Workbench({ header, notice, left, main, right }: WorkbenchProps) {
  const [leftWidth, setLeftWidth] = useState(left?.width.initial ?? 0);
  const [rightWidth, setRightWidth] = useState(right?.width.initial ?? 0);
  const reduceMotion = useReducedMotion();

  return (
    <div className="flex h-full min-h-0 flex-col">
      {header}
      {notice}
      <div className="flex min-h-0 flex-1 overflow-hidden">
        {left && (
          <>
            <aside
              aria-label={left.label}
              className="shrink-0 overflow-hidden bg-canvas"
              style={{ width: leftWidth }}
            >
              {left.content}
            </aside>
            <ResizeHandle
              label={`调整${left.label}的宽度`}
              width={leftWidth}
              bounds={left.width}
              side="left"
              onChange={setLeftWidth}
            />
          </>
        )}
        <section aria-label={main.label} className="relative min-w-0 flex-1 bg-surface">
          {main.content}
        </section>
        <AnimatePresence initial={false}>
          {right?.open && (
            <motion.div
              key="right"
              className="flex shrink-0"
              initial={reduceMotion ? { opacity: 0 } : { width: 0, opacity: 0 }}
              animate={{ width: 'auto', opacity: 1 }}
              exit={reduceMotion ? { opacity: 0 } : { width: 0, opacity: 0 }}
              transition={{ duration: 0.25, ease: [0.2, 0, 0, 1] }}
            >
              <ResizeHandle
                label={`调整${right.label}的宽度`}
                width={rightWidth}
                bounds={right.width}
                side="right"
                onChange={setRightWidth}
              />
              {/* 右栏的地标由内容自己给出（InspectorPanel 是 aside），这里不再套一层。 */}
              <div className="h-full overflow-hidden" style={{ width: rightWidth }}>
                {right.content}
              </div>
            </motion.div>
          )}
        </AnimatePresence>
      </div>
    </div>
  );
}
