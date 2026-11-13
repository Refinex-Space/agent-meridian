import type { ReactNode } from 'react';

import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbLink,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from '@/components/ui/breadcrumb';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/cn';

export interface Crumb {
  label: string;
  /** 没有 href 的一项是当前页。 */
  href?: string;
}

export interface PageHeaderProps {
  title: ReactNode;
  /** 页面所在的层级；通常放在顶栏，详情页的页头里也可以放。 */
  breadcrumb?: readonly Crumb[];
  /** 主操作在最右；一页通常只有一个 primary。 */
  actions?: ReactNode;
  /** 标题下的一行：状态、ID、更新时间，分列对齐，不用中点拼接。 */
  meta?: ReactNode;
  /** 标题区下方的标签页（详情页模板）。 */
  children?: ReactNode;
  /** 加载中：标题与元信息的骨架，几何与最终一致。 */
  loading?: boolean;
  className?: string;
}

/**
 * 页面标题区：面包屑、标题、主操作，详情页再加一行元信息与标签页。左边缘与内容区对齐。
 */
export function PageHeader({
  title,
  breadcrumb,
  actions,
  meta,
  children,
  loading = false,
  className,
}: PageHeaderProps) {
  return (
    <header
      data-slot="page-header"
      className={cn('flex flex-col gap-4 pt-6', !children && 'pb-6', className)}
    >
      {breadcrumb && breadcrumb.length > 0 && (
        <Breadcrumb>
          <BreadcrumbList>
            {breadcrumb.map((crumb, i) => (
              <BreadcrumbItemGroup key={`${crumb.label}-${i}`} crumb={crumb} first={i === 0} />
            ))}
          </BreadcrumbList>
        </Breadcrumb>
      )}
      <div className="flex min-h-8 items-center gap-6">
        <div className="min-w-0 flex-1">
          {loading ? (
            <Skeleton className="h-7 w-56" />
          ) : (
            <h1 className="truncate text-22 leading-heading font-semibold text-ink">{title}</h1>
          )}
        </div>
        {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
      </div>
      {meta &&
        (loading ? (
          <Skeleton className="-mt-2 h-5 w-96" />
        ) : (
          <div className="-mt-2 flex min-h-5 flex-wrap items-center gap-x-4 gap-y-1 text-13 text-ink-2">
            {meta}
          </div>
        ))}
      {children}
    </header>
  );
}

function BreadcrumbItemGroup({ crumb, first }: { crumb: Crumb; first: boolean }) {
  return (
    <>
      {!first && <BreadcrumbSeparator />}
      <BreadcrumbItem>
        {crumb.href ? (
          <BreadcrumbLink href={crumb.href}>{crumb.label}</BreadcrumbLink>
        ) : (
          <BreadcrumbPage>{crumb.label}</BreadcrumbPage>
        )}
      </BreadcrumbItem>
    </>
  );
}
