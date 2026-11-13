/*
 * 来源：shadcn/ui new-york-v4 `tabs`（ui.shadcn.com/r，2026-11 取用）。与上游的差异：
 * - 只保留上游的 line 形态并改为默认：去掉灰底胶囊分段控件；
 * - 标签页与内容左边缘对齐，整组下方一条刻线；当前页为墨色文字加 2px 海图蓝下划线，其余为石墨色；
 * - 去掉竖排方向、阴影、dark: 分支与 ring 焦点（全局 :focus-visible）。
 */
import { Tabs as TabsPrimitive } from 'radix-ui';
import type { ComponentProps } from 'react';

import { cn } from '@/lib/cn';

/** 标签页容器。 */
export function Tabs({ className, ...props }: ComponentProps<typeof TabsPrimitive.Root>) {
  return <TabsPrimitive.Root data-slot="tabs" className={cn('flex flex-col', className)} {...props} />;
}

/** 标签行：下方一条刻线。 */
export function TabsList({ className, ...props }: ComponentProps<typeof TabsPrimitive.List>) {
  return (
    <TabsPrimitive.List
      data-slot="tabs-list"
      className={cn('flex items-end gap-4 border-b border-rule', className)}
      {...props}
    />
  );
}

/** 单个标签：当前页墨色加海图蓝下划线。 */
export function TabsTrigger({ className, ...props }: ComponentProps<typeof TabsPrimitive.Trigger>) {
  return (
    <TabsPrimitive.Trigger
      data-slot="tabs-trigger"
      className={cn(
        'relative -mb-px inline-flex h-9 items-center gap-2 text-13 font-medium whitespace-nowrap text-ink-2',
        'transition-colors duration-150 ease-out hover:text-ink',
        'after:absolute after:inset-x-0 after:bottom-0 after:h-0.5 after:bg-accent after:opacity-0 after:transition-opacity after:duration-150',
        'data-[state=active]:text-ink data-[state=active]:after:opacity-100',
        'disabled:cursor-not-allowed disabled:text-ink-disabled',
        className,
      )}
      {...props}
    />
  );
}

/** 标签页内容。 */
export function TabsContent({ className, ...props }: ComponentProps<typeof TabsPrimitive.Content>) {
  return (
    <TabsPrimitive.Content data-slot="tabs-content" className={cn('min-h-0 flex-1', className)} {...props} />
  );
}
