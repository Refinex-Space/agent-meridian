/*
 * 来源：shadcn/ui new-york-v4 `tooltip`（ui.shadcn.com/r，2026-11 取用）。与上游的差异：
 * - 不用反色（墨底白字）：浮层底 surface-raised、1px 刻线、唯一的浮层阴影、8px 圆角，墨色 12px 文字；
 * - 去掉箭头；出现时 150ms 的不透明度加 2% 缩放，减少动效时只有不透明度；
 * - Provider 默认延迟 300ms（上游为 0），扫过时不闪；动画不依赖 tw-animate-css，关键帧在 tokens.css。
 */
import { Tooltip as TooltipPrimitive } from 'radix-ui';
import type { ComponentProps } from 'react';

import { cn } from '@/lib/cn';

/** 提示的全局设置，放在应用根部。 */
export function TooltipProvider({
  delayDuration = 300,
  ...props
}: ComponentProps<typeof TooltipPrimitive.Provider>) {
  return <TooltipPrimitive.Provider data-slot="tooltip-provider" delayDuration={delayDuration} {...props} />;
}

/** 提示：补充信息放在这里，不写成区块下的小字。 */
export function Tooltip(props: ComponentProps<typeof TooltipPrimitive.Root>) {
  return <TooltipPrimitive.Root data-slot="tooltip" {...props} />;
}

export function TooltipTrigger(props: ComponentProps<typeof TooltipPrimitive.Trigger>) {
  return <TooltipPrimitive.Trigger data-slot="tooltip-trigger" {...props} />;
}

export function TooltipContent({
  className,
  sideOffset = 6,
  ...props
}: ComponentProps<typeof TooltipPrimitive.Content>) {
  return (
    <TooltipPrimitive.Portal>
      <TooltipPrimitive.Content
        data-slot="tooltip-content"
        sideOffset={sideOffset}
        className={cn(
          'z-50 max-w-72 origin-(--radix-tooltip-content-transform-origin) rounded-overlay border border-rule bg-surface-raised px-2 py-1 text-12 text-ink shadow-overlay',
          'motion-safe:animate-overlay-in motion-reduce:animate-fade-in',
          className,
        )}
        {...props}
      />
    </TooltipPrimitive.Portal>
  );
}
