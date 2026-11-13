/*
 * 来源：shadcn/ui new-york-v4 `radio-group`（shadcn CLI 4.23.0 生成，2026-11-13）。与上游的差异：
 * - 16px 圆形（圆形符号允许 rounded-full），控件边界 rule-strong；选中时边框与中心 6px 圆点为海图蓝，
 *   圆点是控件自身的形状而不是上游的 lucide CircleIcon；
 * - 去掉 shadow-xs、dark: 分支与 3px ring 光晕；禁用用 surface-inset 底与 rule 边，不用整体半透明；
 * - 组内默认竖排、行距 8px。
 */
import { RadioGroup as RadioGroupPrimitive } from 'radix-ui';
import type { ComponentProps } from 'react';

import { cn } from '@/lib/cn';

/** 单选组：选项不超过 5 个时用它，更多时用 Select。 */
export function RadioGroup({ className, ...props }: ComponentProps<typeof RadioGroupPrimitive.Root>) {
  return (
    <RadioGroupPrimitive.Root data-slot="radio-group" className={cn('grid gap-2', className)} {...props} />
  );
}

/** 单个选项：配合 Label（htmlFor）给出可访问名称。 */
export function RadioGroupItem({ className, ...props }: ComponentProps<typeof RadioGroupPrimitive.Item>) {
  return (
    <RadioGroupPrimitive.Item
      data-slot="radio-group-item"
      className={cn(
        'peer inline-flex size-4 shrink-0 items-center justify-center rounded-full border border-rule-strong bg-surface',
        'transition-colors duration-150 ease-out hover:border-ink-2 data-[state=checked]:border-accent',
        'aria-invalid:border-danger',
        'disabled:cursor-not-allowed disabled:border-rule disabled:bg-surface-inset',
        className,
      )}
      {...props}
    >
      <RadioGroupPrimitive.Indicator
        data-slot="radio-group-indicator"
        className="size-1.5 rounded-full bg-accent in-disabled:bg-ink-disabled"
      />
    </RadioGroupPrimitive.Item>
  );
}
