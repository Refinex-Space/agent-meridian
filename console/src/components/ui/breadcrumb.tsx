/*
 * 来源：shadcn/ui new-york-v4 `breadcrumb`（shadcn CLI 4.23.0 生成，2026-11-13）。与上游的差异：
 * - 13px 石墨色，链接悬停变墨色；当前页墨色；间隔 8px；
 * - 分隔符是一条斜线文字"/"（设计定稿的顶栏"会话 / ses_…"），不用 ChevronRight 图标；
 * - 当前页去掉 role="link" 与 aria-disabled（它不是链接，只保留 aria-current="page"）；
 * - 去掉 BreadcrumbEllipsis：控制台的层级不超过三级，不折叠；可访问名称改为中文"面包屑"。
 */
import { Slot } from 'radix-ui';
import type { ComponentProps } from 'react';

import { cn } from '@/lib/cn';

/** 面包屑：所在位置的层级，放在顶栏或页面标题上方。 */
export function Breadcrumb(props: ComponentProps<'nav'>) {
  return <nav aria-label="面包屑" data-slot="breadcrumb" {...props} />;
}

export function BreadcrumbList({ className, ...props }: ComponentProps<'ol'>) {
  return (
    <ol
      data-slot="breadcrumb-list"
      className={cn('flex min-w-0 items-center gap-2 text-13 text-ink-2', className)}
      {...props}
    />
  );
}

export function BreadcrumbItem({ className, ...props }: ComponentProps<'li'>) {
  return (
    <li
      data-slot="breadcrumb-item"
      className={cn('inline-flex min-w-0 items-center', className)}
      {...props}
    />
  );
}

export function BreadcrumbLink({
  asChild,
  className,
  ...props
}: ComponentProps<'a'> & { asChild?: boolean }) {
  const Comp = asChild ? Slot.Root : 'a';
  return (
    <Comp
      data-slot="breadcrumb-link"
      className={cn('truncate rounded-control transition-colors duration-150 hover:text-ink', className)}
      {...props}
    />
  );
}

export function BreadcrumbPage({ className, ...props }: ComponentProps<'span'>) {
  return (
    <span
      data-slot="breadcrumb-page"
      aria-current="page"
      className={cn('truncate text-ink', className)}
      {...props}
    />
  );
}

export function BreadcrumbSeparator({ className, ...props }: ComponentProps<'li'>) {
  return (
    <li
      data-slot="breadcrumb-separator"
      role="presentation"
      aria-hidden="true"
      className={cn('text-ink-2 select-none', className)}
      {...props}
    >
      /
    </li>
  );
}
