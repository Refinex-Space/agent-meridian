import type { ReactNode } from 'react';

import { cn } from '@/lib/cn';

export interface StateLayoutProps {
  /** 插画或图标。 */
  figure?: ReactNode;
  title: ReactNode;
  /** 标题下的一两句话（原因、下一步）。 */
  children?: ReactNode;
  actions?: ReactNode;
  /** region：数据区域的中央（区域级空态、错误、无权限）；inline：面板或对话框里的左对齐小块。 */
  size?: 'region' | 'inline';
  role?: 'alert' | 'status';
  className?: string;
}

/**
 * 空态、错误、无权限三种状态共用的版式（内部组件）：区域级居中、行内左对齐，留白来自 4px 阶梯。
 */
export function StateLayout({
  figure,
  title,
  children,
  actions,
  size = 'region',
  role,
  className,
}: StateLayoutProps) {
  if (size === 'inline') {
    return (
      <div role={role} className={cn('flex items-start gap-3 text-13', className)}>
        {figure && <span className="flex h-5 shrink-0 items-center">{figure}</span>}
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <p className="leading-5 font-medium text-ink">{title}</p>
          {children && <div className="flex flex-col gap-1 text-ink-2">{children}</div>}
          {actions && <div className="flex flex-wrap items-center gap-2 pt-2">{actions}</div>}
        </div>
      </div>
    );
  }
  return (
    <div
      role={role}
      className={cn('flex flex-col items-center justify-center gap-4 px-6 py-12 text-center', className)}
    >
      {figure}
      <div className="flex max-w-120 flex-col items-center gap-2">
        <p className="text-16 leading-heading font-medium text-ink">{title}</p>
        {children && <div className="flex flex-col items-center gap-1 text-13 text-ink-2">{children}</div>}
      </div>
      {actions && <div className="flex flex-wrap items-center justify-center gap-2">{actions}</div>}
    </div>
  );
}
