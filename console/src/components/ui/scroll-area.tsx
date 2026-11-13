/*
 * 来源：shadcn/ui new-york-v4 `scroll-area`（ui.shadcn.com/r，2026-11 取用）。与上游的差异：
 * - 滚动条 6px 宽，滑块用控件边界色 rule-strong，悬停时加深；去掉 ring 焦点（全局 :focus-visible）；
 * - 新增 `viewportRef` 与 `viewportClassName`：虚拟滚动需要拿到真正滚动的元素；
 * - 新增 `label`：给出时视口可用键盘聚焦后滚动（role="region"），内容里没有可聚焦元素时必须给（WCAG 2.1.1）。
 */
import { ScrollArea as ScrollAreaPrimitive } from 'radix-ui';
import type { ComponentProps, Ref } from 'react';

import { cn } from '@/lib/cn';

export interface ScrollAreaProps extends ComponentProps<typeof ScrollAreaPrimitive.Root> {
  viewportRef?: Ref<HTMLDivElement>;
  viewportClassName?: string;
  /** 滚动区域的可访问名称；给出时视口可聚焦。 */
  label?: string;
}

/** 带细滚动条的滚动区域。 */
export function ScrollArea({
  className,
  viewportClassName,
  viewportRef,
  label,
  children,
  ...props
}: ScrollAreaProps) {
  return (
    <ScrollAreaPrimitive.Root
      data-slot="scroll-area"
      className={cn('relative overflow-hidden', className)}
      {...props}
    >
      <ScrollAreaPrimitive.Viewport
        ref={viewportRef}
        role={label ? 'region' : undefined}
        aria-label={label}
        tabIndex={label ? 0 : undefined}
        data-slot="scroll-area-viewport"
        className={cn('size-full rounded-[inherit]', viewportClassName)}
      >
        {children}
      </ScrollAreaPrimitive.Viewport>
      <ScrollBar />
      <ScrollAreaPrimitive.Corner />
    </ScrollAreaPrimitive.Root>
  );
}

export function ScrollBar({
  className,
  orientation = 'vertical',
  ...props
}: ComponentProps<typeof ScrollAreaPrimitive.ScrollAreaScrollbar>) {
  return (
    <ScrollAreaPrimitive.ScrollAreaScrollbar
      data-slot="scroll-area-scrollbar"
      orientation={orientation}
      className={cn(
        'flex touch-none p-px select-none',
        orientation === 'vertical' && 'h-full w-1.5',
        orientation === 'horizontal' && 'h-1.5 flex-col',
        className,
      )}
      {...props}
    >
      <ScrollAreaPrimitive.ScrollAreaThumb
        data-slot="scroll-area-thumb"
        className="relative flex-1 rounded-full bg-rule-strong/60 transition-colors duration-150 hover:bg-rule-strong"
      />
    </ScrollAreaPrimitive.ScrollAreaScrollbar>
  );
}
