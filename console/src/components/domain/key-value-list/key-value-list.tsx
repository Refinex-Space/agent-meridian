import type { ReactNode } from 'react';

import { InfoIcon } from '@/components/ui/glyphs';
import { Icon } from '@/components/ui/icon';
import { Skeleton } from '@/components/ui/skeleton';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
import { cn } from '@/lib/cn';

export interface KeyValueItem {
  label: string;
  /** 值；null、undefined 与空串显示为"未设置"（石墨色）。 */
  value: ReactNode;
  /** 补充说明：放在标签旁 info 图标的提示里，不写成小字。 */
  hint?: string;
  /** 机器文本（ID、模型名、路径）用等宽字体。 */
  mono?: boolean;
}

export interface KeyValueListProps {
  items: readonly KeyValueItem[];
  /** 两栏在 1280px 以下合为一栏。 */
  columns?: 1 | 2;
  /** 加载中：按 `items` 的条数（没有时 4 条）画同样行高的骨架。 */
  loading?: boolean;
  className?: string;
}

function isEmpty(value: ReactNode): boolean {
  return value === null || value === undefined || value === '';
}

/**
 * 详情页的属性列表：标签 160px 石墨色，值墨色；每行 36px 高、底部一条刻线。
 */
export function KeyValueList({ items, columns = 1, loading = false, className }: KeyValueListProps) {
  const grid = cn('grid gap-x-12', columns === 2 && 'xl:grid-cols-2', className);
  if (loading) {
    return (
      <div data-slot="key-value-list" aria-busy className={grid}>
        {Array.from({ length: items.length || 4 }, (_, i) => (
          <div
            key={i}
            className="grid min-h-9 grid-cols-[160px_minmax(0,1fr)] items-center gap-4 border-b border-rule"
          >
            <Skeleton className="h-3 w-16" />
            <Skeleton className="h-3 w-40" />
          </div>
        ))}
      </div>
    );
  }
  return (
    <dl data-slot="key-value-list" className={grid}>
      {items.map(({ label, value, hint, mono }) => (
        <div
          key={label}
          className="grid min-h-9 grid-cols-[160px_minmax(0,1fr)] items-baseline gap-4 border-b border-rule py-2 text-13"
        >
          <dt className="flex items-center gap-1 text-ink-2">
            {label}
            {hint && (
              <Tooltip>
                <TooltipTrigger asChild>
                  <button
                    type="button"
                    aria-label={`${label}的说明`}
                    className="inline-flex rounded-control text-ink-2 hover:text-ink"
                  >
                    <Icon glyph={InfoIcon} />
                  </button>
                </TooltipTrigger>
                <TooltipContent>{hint}</TooltipContent>
              </Tooltip>
            )}
          </dt>
          <dd className={cn('min-w-0 break-words text-ink', mono && 'font-mono text-12')}>
            {isEmpty(value) ? <span className="font-sans text-13 text-ink-2">未设置</span> : value}
          </dd>
        </div>
      ))}
    </dl>
  );
}
