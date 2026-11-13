import type { ReactNode } from 'react';

import { EmptyChartIllustration, EmptyClearIllustration, EmptySearchIllustration } from '@/assets/icons';
import { Button } from '@/components/ui/button';

import { StateLayout } from './state-layout';

export interface EmptyStateProps {
  /**
   * first-use：首次使用，这里还没有任何对象，给出主操作；filtered：筛选后没有匹配项，给出"清除筛选"；
   * clear：需要处理的事项都处理完了（例如没有待处理动作），不需要操作。
   */
  variant: 'first-use' | 'filtered' | 'clear';
  /** 一句话：这里会出现什么，或为什么是空的。 */
  title: string;
  /** first-use 的主操作（例如"创建智能体"按钮）。 */
  action?: ReactNode;
  /** filtered 时清除筛选。 */
  onClearFilters?: () => void;
  /** region：数据区域中央，带插画；inline：面板里的一行，不带插画。 */
  size?: 'region' | 'inline';
  className?: string;
}

const FIGURE = {
  'first-use': EmptyChartIllustration,
  filtered: EmptySearchIllustration,
  clear: EmptyClearIllustration,
};

/**
 * 空态：首次使用一句话加主操作；筛选为空提供清除筛选。不写营销文案，插画只有 128×96。
 */
export function EmptyState({
  variant,
  title,
  action,
  onClearFilters,
  size = 'region',
  className,
}: EmptyStateProps) {
  const Figure = FIGURE[variant];
  const actions =
    variant === 'filtered' && onClearFilters ? (
      <Button size={size === 'inline' ? 'sm' : 'md'} onClick={onClearFilters}>
        清除筛选
      </Button>
    ) : (
      action
    );
  return (
    <StateLayout
      size={size}
      role="status"
      figure={size === 'region' ? <Figure className="text-rule-strong" /> : undefined}
      title={title}
      actions={actions}
      className={className}
    />
  );
}
