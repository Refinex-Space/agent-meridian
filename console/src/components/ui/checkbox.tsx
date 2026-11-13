/*
 * 来源：shadcn/ui new-york-v4 `checkbox`（shadcn CLI 4.23.0 生成，2026-11-13）。与上游的差异：
 * - 16px 方框、4px 圆角、控件边界 rule-strong；选中为海图蓝底、on-accent 记号，去掉 shadow-xs、dark: 分支与 3px ring 光晕；
 * - 记号不用图标（图标只有 16、20、24 三档，放不进 16px 的方框）：对勾与半选的短横是控件自身的 1.75px 线条；
 * - 支持 `checked="indeterminate"`（表格的部分选中）；禁用用 surface-inset 底与 rule 边，不用整体半透明；去掉 "use client"。
 */
import { Checkbox as CheckboxPrimitive } from 'radix-ui';
import type { ComponentProps } from 'react';

import { cn } from '@/lib/cn';

/** 复选框：配合 Label 使用，或用 aria-label 给出可访问名称（例如表格的行选择）。 */
export function Checkbox({ className, ...props }: ComponentProps<typeof CheckboxPrimitive.Root>) {
  return (
    <CheckboxPrimitive.Root
      data-slot="checkbox"
      className={cn(
        'peer group/checkbox inline-flex size-4 shrink-0 items-center justify-center rounded-control border border-rule-strong bg-surface text-on-accent',
        'transition-colors duration-150 ease-out hover:border-ink-2',
        'data-[state=checked]:border-accent data-[state=checked]:bg-accent data-[state=indeterminate]:border-accent data-[state=indeterminate]:bg-accent',
        'aria-invalid:border-danger',
        'disabled:cursor-not-allowed disabled:border-rule disabled:bg-surface-inset disabled:text-ink-disabled disabled:data-[state=checked]:border-rule disabled:data-[state=checked]:bg-surface-inset disabled:data-[state=indeterminate]:border-rule disabled:data-[state=indeterminate]:bg-surface-inset',
        className,
      )}
      {...props}
    >
      <CheckboxPrimitive.Indicator data-slot="checkbox-indicator" className="flex">
        <svg viewBox="0 0 16 16" aria-hidden className="size-3.5" fill="none" stroke="currentColor">
          <path
            className="group-data-[state=indeterminate]/checkbox:hidden"
            d="M3.5 8.25 6.5 11.25 12.5 4.75"
            strokeWidth="1.75"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
          <path
            className="hidden group-data-[state=indeterminate]/checkbox:block"
            d="M4 8h8"
            strokeWidth="1.75"
            strokeLinecap="round"
          />
        </svg>
      </CheckboxPrimitive.Indicator>
    </CheckboxPrimitive.Root>
  );
}
