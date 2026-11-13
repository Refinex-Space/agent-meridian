/*
 * 来源：shadcn/ui new-york-v4 `switch`（shadcn CLI 4.23.0 生成，2026-11-13）。与上游的差异：
 * - 不做胶囊：轨道 36×20、滑块 14px，都用 4px 圆角，像仪器面板上的拨动开关；
 * - 关闭时 surface 底、rule-strong 边、石墨滑块（控件边界 3:1）；打开时海图蓝底、on-accent 滑块；
 * - 滑块位移 150ms ease-out，减少动效时不位移动画；去掉 shadow-xs、dark: 分支、3px ring 光晕与 sm 尺寸；
 * - 禁用用 surface-inset 底与 rule 边，不用整体半透明；去掉 "use client"。
 */
import { Switch as SwitchPrimitive } from 'radix-ui';
import type { ComponentProps } from 'react';

import { cn } from '@/lib/cn';

/** 开关：立即生效的二元设置（乐观更新只用于这类可撤销的操作）。需要提交的表单项用复选框。 */
export function Switch({ className, ...props }: ComponentProps<typeof SwitchPrimitive.Root>) {
  return (
    <SwitchPrimitive.Root
      data-slot="switch"
      className={cn(
        'peer group/switch inline-flex h-5 w-9 shrink-0 items-center rounded-control border border-rule-strong bg-surface px-px',
        'transition-colors duration-150 ease-out hover:border-ink-2',
        'data-[state=checked]:border-accent data-[state=checked]:bg-accent data-[state=checked]:hover:border-accent-hover data-[state=checked]:hover:bg-accent-hover',
        'disabled:cursor-not-allowed disabled:border-rule disabled:bg-surface-inset disabled:data-[state=checked]:border-rule disabled:data-[state=checked]:bg-surface-inset',
        className,
      )}
      {...props}
    >
      <SwitchPrimitive.Thumb
        data-slot="switch-thumb"
        className={cn(
          'pointer-events-none block size-3.5 rounded-control bg-ink-2',
          'motion-safe:transition-transform motion-safe:duration-150 motion-safe:ease-out',
          'data-[state=checked]:translate-x-4 data-[state=checked]:bg-on-accent',
          'group-disabled/switch:bg-ink-disabled',
        )}
      />
    </SwitchPrimitive.Root>
  );
}
