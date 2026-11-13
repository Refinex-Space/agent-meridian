/*
 * 来源：shadcn/ui new-york-v4 `skeleton`（ui.shadcn.com/r，2026-11 取用）。与上游的差异：
 * - 颜色用刻线色，圆角 4px；动画是柔和的不透明度呼吸（tokens.css 的 skeleton），减少动效时静止；
 * - 对读屏隐藏：加载状态由所在区域的 aria-busy 表达。
 * 骨架的几何必须与最终布局一致（行高、列宽、图标位置），显示时机用 useDelayedVisible（150ms 内返回不显示，显示后至少 300ms）。
 */
import type { ComponentProps } from 'react';

import { cn } from '@/lib/cn';

/** 骨架块：按最终内容的尺寸给出宽高。 */
export function Skeleton({ className, ...props }: ComponentProps<'div'>) {
  return (
    <div
      data-slot="skeleton"
      aria-hidden
      className={cn('rounded-control bg-rule/70 motion-safe:animate-skeleton', className)}
      {...props}
    />
  );
}
