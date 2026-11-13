/*
 * 来源：shadcn/ui new-york-v4 `popover`（shadcn CLI 4.23.0 生成，2026-11-13）。与上游的差异：
 * - surface-raised 底、1px 刻线、8px 圆角、唯一的浮层阴影，内边距 12px、13px 文字；
 * - 出现 150ms 不透明度加 2% 缩放，退出 ease-in，减少动效时只有不透明度；动画不依赖 tw-animate-css；
 * - 默认左对齐触发器（上游居中），与"布局默认左对齐"一致；
 * - 去掉 PopoverHeader、PopoverTitle、PopoverDescription（上游新增的排版槽位，内容由调用方组织）；去掉 "use client"。
 */
import { Popover as PopoverPrimitive } from 'radix-ui';
import type { ComponentProps } from 'react';

import { cn } from '@/lib/cn';

/** 弹出层：点击触发、可交互的小面板（筛选条件、列显隐、补充表单）。只读的补充说明用 Tooltip。 */
export function Popover(props: ComponentProps<typeof PopoverPrimitive.Root>) {
  return <PopoverPrimitive.Root data-slot="popover" {...props} />;
}

export function PopoverTrigger(props: ComponentProps<typeof PopoverPrimitive.Trigger>) {
  return <PopoverPrimitive.Trigger data-slot="popover-trigger" {...props} />;
}

export function PopoverAnchor(props: ComponentProps<typeof PopoverPrimitive.Anchor>) {
  return <PopoverPrimitive.Anchor data-slot="popover-anchor" {...props} />;
}

export function PopoverContent({
  className,
  align = 'start',
  sideOffset = 4,
  ...props
}: ComponentProps<typeof PopoverPrimitive.Content>) {
  return (
    <PopoverPrimitive.Portal>
      <PopoverPrimitive.Content
        data-slot="popover-content"
        align={align}
        sideOffset={sideOffset}
        className={cn(
          'shadow-overlay z-50 w-72 origin-(--radix-popover-content-transform-origin) rounded-overlay border border-rule bg-surface-raised p-3 text-13 text-ink outline-hidden',
          'motion-safe:data-[state=closed]:animate-overlay-out motion-safe:data-[state=open]:animate-overlay-in',
          'motion-reduce:data-[state=closed]:animate-fade-out motion-reduce:data-[state=open]:animate-fade-in',
          className,
        )}
        {...props}
      />
    </PopoverPrimitive.Portal>
  );
}
