/*
 * 来源：shadcn/ui new-york-v4 `label`（shadcn CLI 4.23.0 生成，2026-11-13）。与上游的差异：
 * - 13px、500 字重、墨色；对应控件禁用时文字变为 ink-disabled，不用整体半透明；
 * - 去掉 "use client"（不是 RSC 工程）。
 */
import { Label as LabelPrimitive } from 'radix-ui';
import type { ComponentProps } from 'react';

import { cn } from '@/lib/cn';

/** 表单标签：每个控件都要有，清爽不能牺牲可访问名称。 */
export function Label({ className, ...props }: ComponentProps<typeof LabelPrimitive.Root>) {
  return (
    <LabelPrimitive.Root
      data-slot="label"
      className={cn(
        'flex items-center gap-2 text-13 leading-5 font-medium text-ink select-none',
        'group-data-[disabled=true]:text-ink-disabled peer-disabled:cursor-not-allowed peer-disabled:text-ink-disabled',
        className,
      )}
      {...props}
    />
  );
}
