/*
 * 来源：shadcn/ui new-york-v4 `table`（shadcn CLI 4.23.0 生成，2026-11-13）。与上游的差异：
 * - 只有分隔线，没有斑马纹与卡片：行高 40px、底部 1px 刻线；表头 32px、12px 石墨色 500 字重；单元格左右 12px、13px 文字、行高 1.4；
 * - 悬停 surface-hover 底，选中（`data-state=selected`）accent-weak 底；数字列由调用方加 `text-right`（单元格默认 tabular-nums）；
 * - 外层容器新增 `containerClassName`：DataTable 的虚拟滚动与吸顶表头需要把滚动交给外层（`overflow-visible`）；
 * - 表尾用 surface-inset 底；去掉 [role=checkbox] 的 2px 下移修正（本项目的复选框与行文字对齐）。
 */
import type { ComponentProps } from 'react';

import { cn } from '@/lib/cn';

export interface TableProps extends ComponentProps<'table'> {
  containerClassName?: string;
}

/** 表格：列表页的主体。超过 200 行用 DataTable 的虚拟滚动。 */
export function Table({ className, containerClassName, ...props }: TableProps) {
  return (
    <div data-slot="table-container" className={cn('relative w-full overflow-x-auto', containerClassName)}>
      <table
        data-slot="table"
        className={cn('w-full caption-bottom border-collapse text-13 leading-table tabular-nums', className)}
        {...props}
      />
    </div>
  );
}

export function TableHeader({ className, ...props }: ComponentProps<'thead'>) {
  return <thead data-slot="table-header" className={cn('[&_tr]:border-b', className)} {...props} />;
}

export function TableBody({ className, ...props }: ComponentProps<'tbody'>) {
  return <tbody data-slot="table-body" className={className} {...props} />;
}

export function TableFooter({ className, ...props }: ComponentProps<'tfoot'>) {
  return (
    <tfoot
      data-slot="table-footer"
      className={cn('border-t border-rule bg-surface-inset font-medium', className)}
      {...props}
    />
  );
}

/** 行：悬停换底色，选中为海图蓝浅底；不做悬停上浮。 */
export function TableRow({ className, ...props }: ComponentProps<'tr'>) {
  return (
    <tr
      data-slot="table-row"
      className={cn(
        'border-b border-rule transition-colors duration-150 ease-out hover:bg-surface-hover data-[state=selected]:bg-accent-weak',
        className,
      )}
      {...props}
    />
  );
}

/** 表头单元格：12px 石墨色，句子式。 */
export function TableHead({ className, ...props }: ComponentProps<'th'>) {
  return (
    <th
      data-slot="table-head"
      className={cn(
        'h-8 px-3 text-left align-middle text-12 font-medium whitespace-nowrap text-ink-2',
        className,
      )}
      {...props}
    />
  );
}

export function TableCell({ className, ...props }: ComponentProps<'td'>) {
  return <td data-slot="table-cell" className={cn('h-10 px-3 align-middle', className)} {...props} />;
}

export function TableCaption({ className, ...props }: ComponentProps<'caption'>) {
  return (
    <caption data-slot="table-caption" className={cn('mt-3 text-12 text-ink-2', className)} {...props} />
  );
}
