/*
 * 来源：shadcn/ui new-york-v4 `separator`（shadcn CLI 4.23.0 生成，2026-11-13）。与上游的差异：
 * - 颜色用刻线 rule（上游 bg-border）；去掉 "use client"。
 */
import { Separator as SeparatorPrimitive } from 'radix-ui';
import type { ComponentProps } from 'react';

import { cn } from '@/lib/cn';

/** 1px 刻线：层级靠背景层次与刻线表达，不靠卡片与阴影。 */
export function Separator({
  className,
  orientation = 'horizontal',
  decorative = true,
  ...props
}: ComponentProps<typeof SeparatorPrimitive.Root>) {
  return (
    <SeparatorPrimitive.Root
      data-slot="separator"
      decorative={decorative}
      orientation={orientation}
      className={cn(
        'shrink-0 bg-rule data-[orientation=horizontal]:h-px data-[orientation=horizontal]:w-full data-[orientation=vertical]:h-full data-[orientation=vertical]:w-px',
        className,
      )}
      {...props}
    />
  );
}
